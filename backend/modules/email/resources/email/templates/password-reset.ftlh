<#import "layout.ftlh" as layout><@layout.emailLayout footer="Falls du dein Passwort nicht zurücksetzen möchtest, kannst du diese E-Mail ignorieren.">
<tr><td style="padding:32px 32px 16px"><h2>Passwort zurücksetzen</h2><p>Du hast angefordert, dein Passwort bei Voenix Shop zurückzusetzen. Bitte vergib über den Link ein neues Passwort.</p></td></tr>
<tr><td style="padding:0 32px 24px;text-align:center"><a href="${actionUrl}" style="display:inline-block;background:#18181b;color:#fff;padding:14px 32px;border-radius:6px;text-decoration:none">Passwort zurücksetzen</a></td></tr>
<tr><td style="padding:0 32px 24px"><p>Falls der Button nicht funktioniert, kopiere diesen Link in deinen Browser:</p><p style="word-break:break-all">${actionUrl}</p><p>Dieser Link ist zeitlich begrenzt gültig. Danach musst du einen neuen Link anfordern.</p></td></tr>
</@layout.emailLayout>
