import { lang, rememberLang, t, type Lang } from './i18n'

interface Props {
  // Сохраняет выбор на сервере, чтобы и бот писал на этом языке
  onChange: (value: Lang) => Promise<boolean>;
}

// Переключатель RU / EN в шапке приложения
export function LanguageSwitch({ onChange }: Props) {
  const choose = async (value: Lang) => {
    if (value === lang) return;
    if (await onChange(value) && rememberLang(value)) {
      window.location.reload();
    }
  };

  return (
    <div className="lang-switch" role="group" aria-label={t.languageLabel}>
      {(['ru', 'en'] as const).map((value) => (
        <button key={value} type="button" aria-pressed={lang === value} onClick={() => choose(value)}>
          {value.toUpperCase()}
        </button>
      ))}
    </div>
  );
}
