import { useEffect, useRef, useState } from 'react'
import { t } from './i18n'
import { openLink } from './telegram'

// Есть ли в письме внешние картинки (ими рассылки отслеживают открытие)
const REMOTE_IMAGES = /(<img[^>]+src\s*=\s*["']?https?:|url\(\s*["']?https?:|background\s*=\s*["']?https?:)/i;

/**
 * HTML письма в закрытой рамке: скрипты не выполняются (sandbox без allow-scripts),
 * внешние картинки и шрифты блокирует CSP, пока пользователь их не разрешит.
 * Ссылки открываются снаружи. Широкие письма уменьшаются по ширине экрана.
 */
export function MailHtml({ html }: { html: string }) {
  const frame = useRef<HTMLIFrameElement>(null);
  const [images, setImages] = useState(false);
  const [height, setHeight] = useState(200);
  const hasRemote = REMOTE_IMAGES.test(html);

  const imgSrc = images ? 'data: https: http:' : 'data:';
  const doc = `<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src ${imgSrc}; style-src 'unsafe-inline'; font-src data:">
<meta name="viewport" content="width=device-width, initial-scale=1">
<base target="_blank">
<style>
html,body{margin:0;padding:0;background:#fff;color:#1b1f24;}
body{padding:12px;font:15px/1.45 -apple-system,Roboto,'Segoe UI',sans-serif;overflow-wrap:anywhere;}
img{max-width:100%;height:auto;}
a{color:#1a73e8;}
</style></head><body>${html}</body></html>`;

  useEffect(() => {
    const element = frame.current;
    if (!element) return;
    let observer: ResizeObserver | undefined;

    const fit = () => {
      const document = element.contentDocument;
      if (!document?.body) return;
      const body = document.body;
      // Широкое письмо (600-800px) уменьшаем по ширине рамки, а не прокручиваем вбок
      body.style.removeProperty('zoom');
      const available = element.clientWidth;
      const wide = document.documentElement.scrollWidth;
      if (wide > available + 4) body.style.setProperty('zoom', String(available / wide));
      setHeight(Math.ceil(document.documentElement.getBoundingClientRect().height) + 2);
    };

    const onLoad = () => {
      const document = element.contentDocument;
      if (!document) return;
      document.addEventListener('click', (event) => {
        const link = (event.target as Element | null)?.closest?.('a[href]');
        if (!link) return;
        event.preventDefault();
        const href = link.getAttribute('href') ?? '';
        if (/^(https?:|mailto:|tel:)/i.test(href)) openLink(href);
      });
      fit();
      observer = new ResizeObserver(fit);
      observer.observe(document.body);
      // Картинки догружаются после load
      document.querySelectorAll('img').forEach((image) => image.addEventListener('load', fit));
    };

    element.addEventListener('load', onLoad);
    window.addEventListener('resize', fit);
    return () => {
      element.removeEventListener('load', onLoad);
      window.removeEventListener('resize', fit);
      observer?.disconnect();
    };
  }, [doc]);

  return (
    <div className="mail-html">
      {hasRemote && !images && (
        <button type="button" className="link-button mail-html-images" onClick={() => setImages(true)}>
          {t.showImages}
        </button>
      )}
      <iframe
        ref={frame}
        title={t.emailContent}
        className="mail-html-frame"
        // Без allow-scripts код письма не выполняется; allow-same-origin нужен, чтобы
        // подогнать высоту и перехватить ссылки
        sandbox="allow-same-origin"
        srcDoc={doc}
        style={{ height }}
      />
    </div>
  );
}
