import Foundation

/// Arithmetic in GF(2^8) with polynomial 0x11D (docs/PROTOCOL.md §4.2).
public enum GF256 {
    public static let exp: [UInt8] = {
        var table = [UInt8](repeating: 0, count: 512)
        var x = 1
        for i in 0..<255 {
            table[i] = UInt8(x)
            x <<= 1
            if x & 0x100 != 0 { x ^= 0x11D }
        }
        for i in 255..<512 { table[i] = table[i - 255] }
        return table
    }()

    public static let log: [Int] = {
        var table = [Int](repeating: 0, count: 256)
        for i in 0..<255 { table[Int(exp[i])] = i }
        return table
    }()

    /// `mulTable[a << 8 | b] == a * b`. Allocated once and never freed so the
    /// hot loops can index it without bounds checks.
    static let mulTable: UnsafeMutablePointer<UInt8> = {
        let table = UnsafeMutablePointer<UInt8>.allocate(capacity: 65536)
        for a in 0..<256 {
            for b in 0..<256 {
                table[a << 8 | b] = (a == 0 || b == 0) ? 0 : exp[log[a] + log[b]]
            }
        }
        return table
    }()

    @inline(__always)
    public static func mul(_ a: UInt8, _ b: UInt8) -> UInt8 {
        mulTable[Int(a) << 8 | Int(b)]
    }

    public static func div(_ a: UInt8, _ b: UInt8) -> UInt8 {
        precondition(b != 0, "division by zero in GF(256)")
        if a == 0 { return 0 }
        return exp[(log[Int(a)] - log[Int(b)] + 255) % 255]
    }

    public static func pow(_ a: UInt8, _ n: Int) -> UInt8 {
        if n == 0 { return 1 }
        if a == 0 { return 0 }
        return exp[(log[Int(a)] * n) % 255]
    }

    static func multiply(_ a: [[UInt8]], _ b: [[UInt8]]) -> [[UInt8]] {
        let inner = b.count, cols = b[0].count
        return a.map { row in
            (0..<cols).map { c in
                var v: UInt8 = 0
                for i in 0..<inner { v ^= mul(row[i], b[i][c]) }
                return v
            }
        }
    }

    /// Gauss–Jordan inversion. Returns nil if the matrix is singular.
    static func invert(_ matrix: [[UInt8]]) -> [[UInt8]]? {
        let n = matrix.count
        var work = matrix.enumerated().map { i, row in
            row + (0..<n).map { $0 == i ? UInt8(1) : 0 }
        }
        for col in 0..<n {
            guard let pivot = (col..<n).first(where: { work[$0][col] != 0 }) else { return nil }
            work.swapAt(col, pivot)
            let inv = div(1, work[col][col])
            work[col] = work[col].map { mul($0, inv) }
            for r in 0..<n where r != col && work[r][col] != 0 {
                let f = work[r][col]
                let pivotRow = work[col]
                for c in 0..<(2 * n) { work[r][c] ^= mul(f, pivotRow[c]) }
            }
        }
        return work.map { Array($0[n...]) }
    }
}

/// Systematic Reed–Solomon erasure code over GF(2^8). Any `k` of the
/// `k + m` shards rebuild the `k` data shards.
public final class ReedSolomon {
    public let dataShards: Int
    public let parityShards: Int
    /// `(k + m) × k`. The top `k` rows are the identity.
    public let matrix: [[UInt8]]

    private static var cache: [Int: ReedSolomon] = [:]
    private static let cacheLock = NSLock()

    /// Encoding matrices are expensive to build, so codecs are cached per (k, m).
    public static func shared(dataShards k: Int, parityShards m: Int) -> ReedSolomon {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        let key = k << 8 | m
        if let codec = cache[key] { return codec }
        let codec = ReedSolomon(dataShards: k, parityShards: m)
        cache[key] = codec
        return codec
    }

    public init(dataShards k: Int, parityShards m: Int) {
        precondition(k >= 1 && m >= 0 && k + m <= 255, "invalid Reed–Solomon shape")
        dataShards = k
        parityShards = m
        let vandermonde = (0..<(k + m)).map { r in (0..<k).map { c in GF256.pow(UInt8(r), c) } }
        let topInverse = GF256.invert(Array(vandermonde[0..<k]))!
        matrix = GF256.multiply(vandermonde, topInverse)
    }

    /// Computes parity shards. `data[c]` and `parity[j]` each point at
    /// `shardSize` bytes.
    public func encodeParity(data: [UnsafePointer<UInt8>],
                             parity: [UnsafeMutablePointer<UInt8>],
                             shardSize: Int) {
        precondition(data.count == dataShards && parity.count == parityShards)
        for j in 0..<parityShards {
            let out = parity[j]
            out.initialize(repeating: 0, count: shardSize)
            let row = matrix[dataShards + j]
            for c in 0..<dataShards {
                Self.mulAdd(coefficient: row[c], input: data[c], output: out, count: shardSize)
            }
        }
    }

    /// Convenience wrapper over `encodeParity` for byte arrays.
    public func encodeParity(_ data: [[UInt8]]) -> [[UInt8]] {
        let size = data.first?.count ?? 0
        var parity = [[UInt8]](repeating: [UInt8](repeating: 0, count: size), count: parityShards)
        let inputs = data.map { shard -> UnsafeMutablePointer<UInt8> in
            let p = UnsafeMutablePointer<UInt8>.allocate(capacity: max(size, 1))
            p.initialize(from: shard, count: size)
            return p
        }
        let outputs = (0..<parityShards).map { _ in UnsafeMutablePointer<UInt8>.allocate(capacity: max(size, 1)) }
        encodeParity(data: inputs.map { UnsafePointer($0) }, parity: outputs, shardSize: size)
        for j in 0..<parityShards { parity[j] = Array(UnsafeBufferPointer(start: outputs[j], count: size)) }
        inputs.forEach { $0.deallocate() }
        outputs.forEach { $0.deallocate() }
        return parity
    }

    /// Rebuilds missing data shards in place. `shards` has `k + m` entries,
    /// nil where a shard was lost. Returns false if fewer than `k` remain.
    public func reconstruct(_ shards: inout [[UInt8]?]) -> Bool {
        precondition(shards.count == dataShards + parityShards)
        let missing = (0..<dataShards).filter { shards[$0] == nil }
        if missing.isEmpty { return true }
        let present = Array(shards.indices.filter { shards[$0] != nil }.prefix(dataShards))
        guard present.count == dataShards,
              let inverse = GF256.invert(present.map { matrix[$0] }) else { return false }
        let size = shards[present[0]]!.count
        for r in missing {
            var out = [UInt8](repeating: 0, count: size)
            out.withUnsafeMutableBufferPointer { outPtr in
                for (c, index) in present.enumerated() {
                    shards[index]!.withUnsafeBufferPointer { inPtr in
                        Self.mulAdd(coefficient: inverse[r][c], input: inPtr.baseAddress!,
                                    output: outPtr.baseAddress!, count: size)
                    }
                }
            }
            shards[r] = out
        }
        return true
    }

    @inline(__always)
    static func mulAdd(coefficient: UInt8, input: UnsafePointer<UInt8>,
                       output: UnsafeMutablePointer<UInt8>, count: Int) {
        switch coefficient {
        case 0:
            return
        case 1:
            for i in 0..<count { output[i] ^= input[i] }
        default:
            let row = GF256.mulTable + Int(coefficient) << 8
            for i in 0..<count { output[i] ^= row[Int(input[i])] }
        }
    }
}
