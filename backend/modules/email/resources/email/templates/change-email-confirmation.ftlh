<#import "layout.ftlh" as layout><@layout.emailLayout footer="Falls du diese Änderung nicht angefordert hast, kannst du diese E-Mail ignorieren. Deine E-Mail-Adresse bleibt unverändert.">
<tr><td style="padding:32px 32px 16px"><h2>E-Mail-Adresse ändern</h2><p>Du hast eine Änderung deiner E-Mail-Adresse zu <strong>${newEmail}</strong> angefordert. Bitte bestätige die neue E-Mail-Adresse.</p></td></tr>
<tr><td style="padding:0 32px 24px;text-align:center"><a href="${actionUrl}" style="display:inline-block;background:#18181b;color:#fff;padding:14px 32px;border-radius:6px;text-decoration:none">E-Mail-Adresse bestätigen</a></td></tr>
<tr><td style="padding:0 32px 24px"><p>Falls der Button nicht funktioniert, kopiere diesen Link in deinen Browser:</p><p style="word-break:break-all">${actionUrl}</p><p>Dieser Link ist 24 Stunden gültig. Danach musst du die Änderung erneut anfordern.</p></td></tr>
</@layout.emailLayout>
