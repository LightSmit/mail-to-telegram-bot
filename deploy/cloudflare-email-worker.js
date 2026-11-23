// Cloudflare Email Worker для режима «Пересылка без пароля».
// Email Routing передает сюда каждое письмо на *@ваш-домен (правило Catch-all -> Send to a Worker),
// а Worker отправляет его боту целиком на /api/inbound.
//
// Переменные Worker (Settings -> Variables and Secrets):
//   BOT_URL        - адрес бота без / в конце, например https://203-0-113-10.sslip.io
//   INBOUND_SECRET - тот же секрет, что INBOUND_SECRET в .env на сервере (тип Secret)
export default {
  async email(message, env) {
    const raw = await new Response(message.raw).arrayBuffer();
    const response = await fetch(env.BOT_URL + "/api/inbound", {
      method: "POST",
      headers: {
        "Content-Type": "message/rfc822",
        "X-Inbound-Secret": env.INBOUND_SECRET,
        "X-Inbound-To": message.to,
      },
      body: raw,
    });

    if (response.status === 404) {
      // Такого адреса нет (ящик удален или адрес с опечаткой): письмо вернется отправителю
      message.setReject("Unknown address");
    } else if (response.status === 413) {
      message.setReject("Message too large");
    } else if (!response.ok) {
      // Бот недоступен: ошибка заставит почту отправителя повторить доставку позже
      throw new Error("Bot error " + response.status);
    }
  },
};
