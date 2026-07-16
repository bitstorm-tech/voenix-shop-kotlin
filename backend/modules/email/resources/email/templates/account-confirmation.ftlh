<#import "layout.ftlh" as layout><@layout.emailLayout footer="Falls du dich nicht bei Voenix Shop registriert hast, kannst du diese E-Mail ignorieren.">
<tr><td style="padding:32px 32px 16px"><h2>E-Mail-Adresse bestätigen</h2><p>Vielen Dank für deine Registrierung bei Voenix Shop! Bitte bestätige deine E-Mail-Adresse, um dein Konto zu aktivieren.</p></td></tr>
<tr><td style="padding:0 32px 24px;text-align:center"><a href="${actionUrl}" style="display:inline-block;background:#18181b;color:#fff;padding:14px 32px;border-radius:6px;text-decoration:none">E-Mail bestätigen</a></td></tr>
<tr><td style="padding:0 32px 24px"><p>Falls der Button nicht funktioniert, kopiere diesen Link in deinen Browser:</p><p style="word-break:break-all">${actionUrl}</p><p>Dieser Link ist 24 Stunden gültig. Danach musst du eine neue Bestätigungs-E-Mail anfordern.</p></td></tr>
</@layout.emailLayout>
