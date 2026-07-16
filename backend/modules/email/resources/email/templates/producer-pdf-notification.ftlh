<#import "layout.ftlh" as layout><@layout.emailLayout footer="Bei Rückfragen zur Bestellung wenden Sie sich bitte an den Voenix Shop.">
<tr><td style="padding:32px 32px 16px"><h2>Neue Bestellung per SFTP übermittelt</h2><p>${greeting}</p><p>Eine neue Bestellung wurde per SFTP auf Ihren Server hochgeladen.</p></td></tr>
<tr><td style="padding:8px 32px 24px"><p>Bestellnummer: <strong>#${orderId}</strong><br>Dateiname: <strong>${fileName}</strong><br>SFTP-Server: <strong>${serverName}</strong><br>Bestelldatum: <strong>${orderDate}</strong><br>Anzahl Artikel: <strong>${itemCount}</strong></p></td></tr>
</@layout.emailLayout>
