package shop.voenix.production.fulfillment

/**
 * What is known about one package at the moment it is reported as shipped: nothing, a carrier, a
 * number, or both. Everything below the routes works with this value rather than with the request
 * body, so the carrier is an enum from here on and no blank string can travel further.
 */
internal data class Shipment(val carrier: ShippingCarrier?, val trackingNumber: String?)
