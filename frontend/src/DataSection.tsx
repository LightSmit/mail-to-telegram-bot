import { useState } from 'react'
import { t } from './i18n'

interface Props {
  onDelete: () => Promise<boolean>;
}

// Удаление всех данных пользователя: в два нажатия, чтобы не удалить случайно
export function DataSection({ onDelete }: Props) {
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const click = async () => {
    if (!confirming) {
      setConfirming(true);
      return;
    }
    setDeleting(true);
    await onDelete();
    setDeleting(false);
    setConfirming(false);
  };

  return (
    <section className="section" aria-labelledby="data-title">
      <h2 id="data-title" className="section-title">{t.myData}</h2>
      <div className="filters notify">
        <span className="toggle-hint">{t.myDataHint}</span>
        {confirming && (
          <span className="field-hint danger-hint">{t.deleteWarning}</span>
        )}
        <button
          type="button"
          className={confirming ? 'remove confirm danger-button' : 'remove danger-button'}
          disabled={deleting}
          onClick={click}
          onBlur={() => setConfirming(false)}
        >
          {deleting ? t.deleting : confirming ? t.deleteConfirm : t.deleteAll}
        </button>
      </div>
    </section>
  );
}
