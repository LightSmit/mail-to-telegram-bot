import { memo, useMemo } from 'react'

// Анимированное звездное небо за всем приложением. Рисуется кодом (ничего не скачивается),
// в светлой теме Telegram и при «уменьшить движение» выключается через CSS.

const STAR_COUNT = 70;
const KEY = 'sky';

/** Включен ли фон (запоминается на устройстве). */
export function loadSkyEnabled(): boolean {
  try {
    return localStorage.getItem(KEY) !== 'off';
  } catch {
    return true;
  }
}

export function saveSkyEnabled(enabled: boolean): void {
  try {
    localStorage.setItem(KEY, enabled ? 'on' : 'off');
  } catch {
    // хранилище недоступно: настройка действует до закрытия приложения
  }
}

function makeStars() {
  let seed = 7;
  const random = () => {
    seed = (seed * 16807) % 2147483647;
    return seed / 2147483647;
  };
  return Array.from({ length: STAR_COUNT }, (_, index) => {
    const kind = random();
    return {
      id: index,
      className: kind < 0.2 ? 'star bright' : kind < 0.4 ? 'star blue' : kind < 0.65 ? 'star tiny' : 'star',
      style: {
        left: `${(random() * 100).toFixed(1)}%`,
        top: `${(random() * 110).toFixed(1)}%`,
        animationDuration: `${(2.5 + random() * 4).toFixed(1)}s`,
        animationDelay: `-${(random() * 6).toFixed(1)}s`,
      },
    };
  });
}

export const Sky = memo(function Sky() {
  const stars = useMemo(makeStars, []);
  return (
    <div className="sky" aria-hidden="true">
      <div className="sky-drift">
        <div className="nebula nebula-violet" />
        <div className="nebula nebula-blue" />
        {stars.map((star) => <i key={star.id} className={star.className} style={star.style} />)}
      </div>
    </div>
  );
});
