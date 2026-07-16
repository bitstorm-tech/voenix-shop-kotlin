<#macro emailLayout footer>
<!DOCTYPE html>
<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>Voenix Shop</title></head>
<body style="margin:0;padding:0;background:#f4f4f5;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif">
<table role="presentation" cellpadding="0" cellspacing="0" style="width:100%;background:#f4f4f5"><tr><td style="padding:24px 0">
<table role="presentation" cellpadding="0" cellspacing="0" style="width:600px;max-width:100%;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden">
<tr><td style="background:#18181b;color:#fff;padding:24px 32px;text-align:center"><h1 style="margin:0;font-size:24px;font-weight:600">Voenix Shop</h1></td></tr>
<#nested>
<tr><td style="background:#f4f4f5;padding:24px 32px;text-align:center"><p style="margin:0;color:#71717a;font-size:13px;line-height:1.5">${footer}</p><p style="margin:8px 0 0;color:#a1a1aa;font-size:12px">&copy; Voenix Shop</p></td></tr>
</table></td></tr></table></body></html>
</#macro>
