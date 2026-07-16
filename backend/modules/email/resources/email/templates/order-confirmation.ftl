Bestellbestätigung #${orderId}
========================================

Hallo ${customerFirstName},
vielen Dank für deine Bestellung!
Sobald deine Bestellung versendet wurde, erhältst du eine Versandbestätigung per E-Mail.

Bestellnummer: #${orderId}
Bestelldatum:  ${orderDate}

Artikel:
----------------------------------------
<#list items as item>  ${item.articleName} (${item.variantName})
    ${item.quantity}x ${item.unitPrice} = ${item.totalPrice}
</#list>----------------------------------------
Zwischensumme: ${subtotal}
Versand:       ${shippingCost}
Gesamtbetrag:  ${total}

Lieferadresse:
  ${shippingAddress.firstName} ${shippingAddress.lastName}
  ${shippingAddress.street} ${shippingAddress.houseNumber}
  ${shippingAddress.postalCode} ${shippingAddress.city}
  ${shippingAddress.country}

Rechnungsadresse:
  ${billingAddress.firstName} ${billingAddress.lastName}
  ${billingAddress.street} ${billingAddress.houseNumber}
  ${billingAddress.postalCode} ${billingAddress.city}
  ${billingAddress.country}
