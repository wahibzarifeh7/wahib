const nodemailer = require('nodemailer');

// Plain SMTP, so any provider works (Gmail app password, Brevo, Resend SMTP, ...).
// Configure with Netlify env vars: SMTP_HOST, SMTP_PORT (default 465), SMTP_USER,
// SMTP_PASS, and optionally SMTP_FROM (default: "WZExchange <SMTP_USER>").
let transporter = null;

function isConfigured() {
  return !!(process.env.SMTP_HOST && process.env.SMTP_USER && process.env.SMTP_PASS);
}

function getTransporter() {
  if (!transporter) {
    const port = Number(process.env.SMTP_PORT || 465);
    transporter = nodemailer.createTransport({
      host: process.env.SMTP_HOST,
      port,
      secure: port === 465,
      auth: { user: process.env.SMTP_USER, pass: process.env.SMTP_PASS },
    });
  }
  return transporter;
}

async function sendMail({ to, subject, text, html }) {
  const from = process.env.SMTP_FROM || `WZExchange <${process.env.SMTP_USER}>`;
  return getTransporter().sendMail({ from, to, subject, text, html });
}

module.exports = { isConfigured, sendMail };
