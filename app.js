// Scale the 2560×1440 stage to fit the window, keeping the 16:9 frame.
(() => {
  const stage = document.querySelector('.stage');
  const fit = () => {
    const k = Math.min(window.innerWidth / 2560, window.innerHeight / 1440);
    stage.style.transform = `scale(${k})`;
  };
  window.addEventListener('resize', fit);
  fit();
})();
