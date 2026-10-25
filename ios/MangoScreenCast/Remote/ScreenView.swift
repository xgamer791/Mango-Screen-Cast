import AVFoundation
import SwiftUI
import UIKit

/// What the SwiftUI side needs from the screen view: how far it's zoomed,
/// and a way back to the whole screen.
final class ScreenController: ObservableObject {
    @Published fileprivate(set) var zoom: CGFloat = 1
    fileprivate weak var view: ScreenView?

    func showWholeScreen() { view?.showWholeScreen() }
}

/// The mirrored picture, with Holli Bot's Screen controls (ScreenGestures):
/// pinch to zoom, a finger to move around zoomed in or to scroll the Mac,
/// and a tap to click there. A mouse or trackpad on the iPad works the Mac's
/// pointer as its own would. Without a Mac to control, a tap shows or hides
/// the controls and a double tap switches Fill and Fit, as before.
struct ScreenSurface: UIViewRepresentable {
    let renderer: VideoRenderer?
    let controller: ScreenController
    let controlling: Bool
    var demo = false
    let send: (RemoteProtocol.Message) -> Void
    let onTap: () -> Void
    let onDoubleTap: () -> Void
    /// A tap clicked on the Mac (what's being typed starts afresh).
    let onClick: () -> Void

    func makeUIView(context: Context) -> ScreenView {
        let view = ScreenView(renderer: renderer, demo: demo)
        controller.view = view
        update(view)
        return view
    }

    func updateUIView(_ view: ScreenView, context: Context) {
        controller.view = view
        update(view)
    }

    private func update(_ view: ScreenView) {
        let controller = self.controller
        view.send = send
        view.onTap = onTap
        view.onDoubleTap = onDoubleTap
        view.onClick = onClick
        view.onZoom = { zoom in if controller.zoom != zoom { controller.zoom = zoom } }
        view.controlling = controlling
    }

    static func dismantleUIView(_ view: ScreenView, coordinator: ()) {
        view.release()
    }
}

final class ScreenView: UIView {
    var send: ((RemoteProtocol.Message) -> Void)?
    var onTap: (() -> Void)?
    var onDoubleTap: (() -> Void)?
    var onClick: (() -> Void)?
    var onZoom: ((CGFloat) -> Void)?
    var controlling = false {
        didSet { if controlling && !oldValue { sendPictureSize() } }
    }

    private weak var renderer: VideoRenderer?
    private weak var hosted: CALayer?
    private var demoSize: CGSize?
    private(set) var picture = ScreenGestures.View.whole {
        didSet { layoutPicture() }
    }

    init(renderer: VideoRenderer?, demo: Bool) {
        self.renderer = renderer
        super.init(frame: .zero)
        backgroundColor = .black
        clipsToBounds = true
        isMultipleTouchEnabled = true
        isAccessibilityElement = true
        accessibilityIdentifier = "mirroredScreen"
        accessibilityLabel = "Mac screen"
        accessibilityHint = "Tap to click there; pinch to zoom."
        if demo {
            let size = CGSize(width: 1600, height: 1600 * Self.screenAspect)
            let layer = CALayer()
            layer.contents = Self.demoPicture(size).cgImage
            layer.contentsGravity = .resize
            self.layer.addSublayer(layer)
            hosted = layer
            demoSize = size
        } else if let renderer {
            renderer.onPictureSize = { [weak self] _ in self?.sendPictureSize() }
            host(renderer.layer)
        }
        let hover = UIHoverGestureRecognizer(target: self, action: #selector(hovered(_:)))
        addGestureRecognizer(hover)
        let scroll = UIPanGestureRecognizer(target: self, action: #selector(scrolled(_:)))
        scroll.allowedScrollTypesMask = .all
        scroll.allowedTouchTypes = []
        addGestureRecognizer(scroll)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    // MARK: - The picture

    private func host(_ sublayer: CALayer) {
        guard sublayer.superlayer !== layer else { return }
        sublayer.removeFromSuperlayer()
        layer.addSublayer(sublayer)
        hosted = sublayer
        setNeedsLayout()
    }

    /// Lets go of the video layer unless another view has already taken it.
    func release() {
        stopFling()
        if let hosted, hosted.superlayer === layer, demoSize == nil { hosted.removeFromSuperlayer() }
        if renderer?.onPictureSize != nil { renderer?.onPictureSize = nil }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layoutPicture()
        renderer?.viewSize = bounds.size
    }

    /// The picture laid out at its zoomed size, not scaled, so it's drawn
    /// sharp from the full video at every zoom.
    private func layoutPicture() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        hosted?.frame = picture.rect(in: bounds.size)
        CATransaction.commit()
    }

    func showWholeScreen() {
        picture = .whole
        onZoom?(1)
    }

    private var pictureSize: CGSize? { demoSize ?? renderer?.shownPictureSize }

    /// Where the picture is in this view: inside the zoomed layer, fitted or
    /// filled as the layer shows it.
    private func pictureRect() -> CGRect {
        let frame = picture.rect(in: bounds.size)
        guard let size = pictureSize, size.width > 0, size.height > 0 else { return frame }
        let gravity = (hosted as? AVSampleBufferDisplayLayer)?.videoGravity ?? .resizeAspect
        if gravity == .resizeAspectFill {
            let scale = max(frame.width / size.width, frame.height / size.height)
            let width = size.width * scale
            let height = size.height * scale
            return CGRect(x: frame.midX - width / 2, y: frame.midY - height / 2, width: width, height: height)
        }
        return AVMakeRect(aspectRatio: size, insideRect: frame)
    }

    /// `point` as shares of the picture, or nil when it's off the picture.
    private func share(of point: CGPoint, clamped: Bool = false) -> CGPoint? {
        let rect = pictureRect()
        guard rect.width > 0, rect.height > 0 else { return nil }
        var share = CGPoint(x: (point.x - rect.minX) / rect.width, y: (point.y - rect.minY) / rect.height)
        if clamped {
            share = CGPoint(x: min(1, max(0, share.x)), y: min(1, max(0, share.y)))
        } else if !(0...1).contains(share.x) || !(0...1).contains(share.y) {
            return nil
        }
        return share
    }

    private func sendPictureSize() {
        guard controlling, let size = pictureSize, size.width > 0 else { return }
        send?(.picture(width: Int(size.width), height: Int(size.height)))
    }

    // MARK: - Fingers

    private struct Gesture {
        var count: Int
        var center: CGPoint
        var distance: CGFloat
        var start: ScreenGestures.View
        var moved: Bool
        var pinched: Bool
        var swiping = false
        var swipe = ScreenGestures.Swipe()
        var last: CGPoint?
        var at = CGPoint(x: 0.5, y: 0.5)
        var point: CGPoint
    }

    private var fingers: [UITouch] = []
    private var gesture: Gesture?
    private var quietUntil: TimeInterval = 0
    private var lastClick = (time: TimeInterval(0), point: CGPoint.zero, count: 0)
    private var pendingTap: DispatchWorkItem?
    private var lastPlainTap: TimeInterval = 0

    private var now: TimeInterval { CACurrentMediaTime() }

    private func measure() -> (count: Int, center: CGPoint, distance: CGFloat)? {
        guard let first = fingers.first else { return nil }
        let a = first.location(in: self)
        let b = fingers.count > 1 ? fingers[1].location(in: self) : a
        return (fingers.count, CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2), hypot(a.x - b.x, a.y - b.y))
    }

    /// A finger landed or lifted: the gesture carries on from where the picture is.
    private func begin() {
        stopFling()
        guard let m = measure() else {
            gesture = nil
            return
        }
        gesture = Gesture(count: m.count, center: m.center, distance: m.distance, start: picture,
                          moved: gesture?.moved ?? false, pinched: (gesture?.pinched ?? false) || m.count > 1,
                          point: m.center)
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            if touch.type == .indirectPointer {
                pointerDown(touch, event: event)
            } else {
                fingers.append(touch)
            }
        }
        if touches.contains(where: { $0.type != .indirectPointer }) { begin() }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let touch = touches.first(where: { $0.type == .indirectPointer }) { pointerMoved(touch) }
        guard var g = gesture, touches.contains(where: { fingers.contains($0) }), let m = measure(),
              bounds.width > 0, bounds.height > 0 else { return }
        if m.count != g.count { return begin() }
        let far = hypot(m.center.x - g.center.x, m.center.y - g.center.y) > ScreenGestures.slop
        if m.count > 1 {
            // Two fingers: the picture zooms.
            g.moved = true
            picture = .pinched(from: g.start, startX: g.center.x / bounds.width, startY: g.center.y / bounds.height,
                               startDistance: g.distance, toX: m.center.x / bounds.width,
                               y: m.center.y / bounds.height, distance: m.distance)
        } else if g.start.zoomedIn && (g.moved || far) {
            // Zoomed in: the picture moves with the finger.
            g.moved = true
            picture = g.start.panned((m.center.x - g.center.x) / bounds.width, (m.center.y - g.center.y) / bounds.height)
        } else if !g.pinched && (g.swiping || far) && controlling {
            // Not zoomed in: the Mac scrolls under the finger.
            swipe(&g, to: m.center)
            g.moved = true
            g.swiping = true
        } else if far {
            g.moved = true
        }
        g.point = m.center
        gesture = g
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        lifted(touches, cancelled: false)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        lifted(touches, cancelled: true)
    }

    private func lifted(_ touches: Set<UITouch>, cancelled: Bool) {
        if let touch = touches.first(where: { $0.type == .indirectPointer }) { pointerUp(touch, cancelled: cancelled) }
        let fingerTouches = touches.filter { $0.type != .indirectPointer }
        guard !fingerTouches.isEmpty else { return }
        fingers.removeAll { fingerTouches.contains($0) }
        if !fingers.isEmpty { return begin() }
        guard let done = gesture else { return }
        gesture = nil
        if done.swiping {
            // The tap that follows a swipe isn't a click.
            quietUntil = now + ScreenGestures.quietTime
            carryOn(done)
            return
        }
        if done.pinched || (done.moved && done.start.zoomedIn) {
            settle()
            return
        }
        if !done.moved && !cancelled && now >= quietUntil { tapped(at: done.point) }
    }

    private func settle() {
        quietUntil = now + ScreenGestures.quietTime
        picture = picture.settled
        onZoom?(picture.z)
    }

    private func swipe(_ g: inout Gesture, to point: CGPoint) {
        let rect = pictureRect()
        let step = g.swipe.step(origin: g.center, last: g.last ?? g.center, now: point, size: rect.size,
                                time: now * 1000)
        g.last = point
        g.at = share(of: point, clamped: true) ?? g.at
        if step.dx != 0 || step.dy != 0 {
            send?(.wheel(fx: Double(g.at.x), fy: Double(g.at.y), fdx: step.dx, fdy: step.dy))
        }
    }

    private func tapped(at point: CGPoint) {
        guard controlling else { return plainTap() }
        guard let at = share(of: point) else { return }
        let quick = now - lastClick.time < ScreenGestures.doubleTapTime
            && hypot(point.x - lastClick.point.x, point.y - lastClick.point.y) < ScreenGestures.doubleTapDistance
        let count = quick ? min(lastClick.count + 1, 3) : 1
        lastClick = (now, point, count)
        send?(.click(fx: Double(at.x), fy: Double(at.y), button: .left, count: count))
        onClick?()
    }

    /// Not controlling a Mac: one tap shows or hides the controls, two
    /// switch Fill and Fit.
    private func plainTap() {
        if let pendingTap, now - lastPlainTap < 0.3 {
            pendingTap.cancel()
            self.pendingTap = nil
            onDoubleTap?()
            return
        }
        lastPlainTap = now
        let work = DispatchWorkItem { [weak self] in
            self?.pendingTap = nil
            self?.onTap?()
        }
        pendingTap = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3, execute: work)
    }

    // MARK: - Carrying on after a swipe

    private var flingLink: CADisplayLink?
    private var fling = ScreenGestures.Swipe()
    private var flingAt = CGPoint.zero
    private var flingTime = 0.0

    private func carryOn(_ done: Gesture) {
        guard done.swipe.flings(at: now * 1000) else { return }
        fling = done.swipe
        flingAt = done.at
        flingTime = now * 1000
        let link = CADisplayLink(target: self, selector: #selector(flingStep(_:)))
        link.add(to: .main, forMode: .common)
        flingLink = link
    }

    @objc private func flingStep(_ link: CADisplayLink) {
        let time = now * 1000
        let dt = min(50, time - flingTime)
        flingTime = time
        guard let step = fling.fling(dt) else { return stopFling() }
        send?(.wheel(fx: Double(flingAt.x), fy: Double(flingAt.y), fdx: step.dx, fdy: step.dy))
    }

    private func stopFling() {
        flingLink?.invalidate()
        flingLink = nil
    }

    // MARK: - A mouse or trackpad on the iPad

    private var pointerButton: RemoteProtocol.MouseButton?
    private var lastPress = (time: TimeInterval(0), point: CGPoint.zero, count: 0)
    private var lastPoint: TimeInterval = 0
    private var hoverPoint = CGPoint.zero
    private var lastScroll = CGPoint.zero

    @objc private func hovered(_ hover: UIHoverGestureRecognizer) {
        hoverPoint = hover.location(in: self)
        guard controlling, hover.state == .changed || hover.state == .began else { return }
        movePointer(to: hoverPoint)
    }

    /// The Mac's pointer goes where this one is, at most about 60 times a second.
    private func movePointer(to point: CGPoint) {
        guard now - lastPoint > 0.015, let at = share(of: point) else { return }
        lastPoint = now
        send?(.point(fx: Double(at.x), fy: Double(at.y)))
    }

    private func pointerDown(_ touch: UITouch, event: UIEvent?) {
        let point = touch.location(in: self)
        hoverPoint = point
        guard controlling else { return }
        guard let at = share(of: point) else { return }
        let button: RemoteProtocol.MouseButton = event?.buttonMask.contains(.secondary) == true ? .right : .left
        let quick = now - lastPress.time < 0.5 && hypot(point.x - lastPress.point.x, point.y - lastPress.point.y) < 6
        let count = quick ? min(lastPress.count + 1, 3) : 1
        lastPress = (now, point, count)
        pointerButton = button
        send?(.press(fx: Double(at.x), fy: Double(at.y), button: button, down: true, count: count))
    }

    private func pointerMoved(_ touch: UITouch) {
        hoverPoint = touch.location(in: self)
        guard controlling, pointerButton != nil else { return }
        movePointer(to: hoverPoint)
    }

    private func pointerUp(_ touch: UITouch, cancelled: Bool) {
        let point = touch.location(in: self)
        guard controlling else {
            if !cancelled { plainTap() }
            return
        }
        guard let button = pointerButton else { return }
        pointerButton = nil
        let at = share(of: point, clamped: true) ?? .zero
        send?(.press(fx: Double(at.x), fy: Double(at.y), button: button, down: false, count: lastPress.count))
        onClick?()
    }

    /// Two fingers on a trackpad, or a mouse's wheel: the Mac scrolls where
    /// the pointer is (or, with no Mac to control, a zoomed picture moves).
    @objc private func scrolled(_ pan: UIPanGestureRecognizer) {
        let translation = pan.translation(in: self)
        if pan.state == .began { lastScroll = .zero }
        let dx = translation.x - lastScroll.x
        let dy = translation.y - lastScroll.y
        lastScroll = translation
        guard pan.state == .changed, bounds.width > 0, bounds.height > 0 else { return }
        if controlling {
            let rect = pictureRect()
            guard rect.width > 0, rect.height > 0, let at = share(of: hoverPoint, clamped: true) else { return }
            send?(.wheel(fx: Double(at.x), fy: Double(at.y), fdx: -Double(dx / rect.width),
                         fdy: -Double(dy / rect.height)))
        } else if picture.zoomedIn {
            picture = picture.panned(dx / bounds.width, dy / bounds.height)
        }
    }

    // MARK: - Demo (UI tests)

    private static var screenAspect: CGFloat {
        let size = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?.screen.bounds.size
            ?? CGSize(width: 4, height: 3)
        return min(size.width, size.height) / max(size.width, size.height)
    }

    /// A grid to tap on, standing in for a Mac's screen.
    private static func demoPicture(_ size: CGSize) -> UIImage {
        UIGraphicsImageRenderer(size: size).image { context in
            UIColor(red: 0.12, green: 0.14, blue: 0.2, alpha: 1).setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIColor(white: 1, alpha: 0.25).setStroke()
            for i in 0...10 {
                let x = size.width * CGFloat(i) / 10
                let y = size.height * CGFloat(i) / 10
                context.cgContext.move(to: CGPoint(x: x, y: 0))
                context.cgContext.addLine(to: CGPoint(x: x, y: size.height))
                context.cgContext.move(to: CGPoint(x: 0, y: y))
                context.cgContext.addLine(to: CGPoint(x: size.width, y: y))
            }
            context.cgContext.strokePath()
            let text = "Demo Mac screen" as NSString
            text.draw(at: CGPoint(x: 40, y: 40), withAttributes: [
                .font: UIFont.systemFont(ofSize: 64, weight: .bold), .foregroundColor: UIColor.white,
            ])
        }
    }
}
