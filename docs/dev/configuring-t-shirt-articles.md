# Configuring a t-shirt article

This is the operator's manual for putting a t-shirt into the shop. It lists, in
order, what has to exist before a shirt can be sold and produced, and which
screen or file each piece is entered in. Nothing here is code: it is admin UI,
application configuration, and the partner's backoffice.

A mug is produced by a printer this shop pushes a PDF to; a shirt is produced
by the print-on-demand partner **Spreadconnect (SPOD)**, which this shop talks
to over a REST API. That is why a shirt needs more set-up than a mug: a supplier
with an API account, a webhook the partner can reach, and the partner's product
ids on every variant. How the production side works once everything is
configured is described in [SPOD fulfillment](backend/spod-fulfillment.md); the
data model of the article itself is in the
[Article package guide](backend/article-package.md#t-shirts).

## The checklist

| Step | Where | Without it |
| --- | --- | --- |
| 0. Partner account, token, payment method | Spreadconnect backoffice | no token to enter, and orders the partner will not confirm |
| 1. `production.spod` configured | application configuration | the app refuses to start once a SPOD destination exists, and the admin UI refuses to create one |
| 2. Supplier created | `/admin/suppliers/new` | no destination and no article can name it |
| 3. SPOD destination created | `/admin/logistics/destinations` | the job is created but never submitted |
| 4. Webhook subscriptions registered | partner API (`curl`) | orders are produced, but the shop never learns they shipped |
| 5. T-shirt article created | `/admin/articles/tshirts/new` | nothing to sell |
| 6. Test order on staging | shop + `/admin/logistics` | you find the mistakes in step 3 and 5 with a paying customer |

Steps 0–4 are done **once per supplier** (and once per partner installation,
staging and production); step 5 is repeated for every shirt.

## 0. On the Spreadconnect side

Everything in this section happens in the partner's backoffice, not in this
shop. The partner runs two installations, **staging** and **production**, with
separate accounts, tokens, and data, so whatever you do here you do twice —
staging first.

1. **Get an account.** The production account is the normal Spreadconnect
   merchant account. Staging access is not self-service at the time of writing:
   ask the partner's integration support for a staging account, and expect it
   to come with its own login.
2. **Create an API access token** in the backoffice under the API / integration
   settings. It is the value of the `X-SPOD-ACCESS-TOKEN` header and the only
   credential this shop stores (step 3). Treat it like a password: one token per
   installation, and revoke it there if it ever leaks.
3. **Deposit a payment method** on the production account. The partner charges
   an order the moment this shop confirms it; an account without a valid
   payment method does not produce, and the order comes back as
   *needs-action* or *cancelled* and an ops alert.
4. **Check the shipping countries and the shipping origin.** This shop has no
   country gate (ADR 0002, decision 7): an order into a country the partner
   does not ship to is refused at creation, reaches you as an
   `ORDER_CREATE_REJECTED` alert, and is refunded by hand. Know the list.
5. **Look up the product** you want to sell and note its ids — see
   [Before you start: the partner's product ids](#before-you-start-the-partners-product-ids).
   The product catalog, the mockup images, and the size charts are also in the
   backoffice; you will need a front-view image per colour and the size chart
   in step 5.

The partner's own docs are the [API reference](https://api.spreadconnect.app/docs/);
read its *Authentication*, *Subscriptions*, and *Simulation* sections once —
the last one is what makes step 6 possible without shipping a parcel.

## 1. The application configuration

The SPOD channel needs two values in the application configuration, both in the
`production.spod` block of [`application.yaml`](../../backend/app/resources/application.yaml)
(or its environment variables):

```yaml
production:
  spod:
    webhookSecret: ""   # PRODUCTION_SPOD_WEBHOOK_SECRET, at least 32 characters
    alertEmail: ""      # PRODUCTION_SPOD_ALERT_EMAIL
```

- `webhookSecret` is the last path segment of the callback URL the partner
  calls when a parcel ships (`/api/production/webhooks/spod/{secret}`). Generate
  a random string of at least 32 characters, for example with
  `openssl rand -hex 32`, and keep it: you need the same value in step 4.
- `alertEmail` is the shared mailbox that receives the ops alert when a job
  needs a human (a cancelled order, a quarantined job, an order the partner
  refuses). Use a mailbox someone actually reads.

Set **both** or neither. The application checks at startup: one without the
other fails, and both blank while a SPOD destination exists in the database
fails too. The admin UI enforces the same rule from the other side and refuses
to create a SPOD destination while the block is empty, so do this step first.
Restart the application after changing the configuration.

## 2. The supplier

Create the supplier under `/admin/suppliers/new`. Only the name is required; the
address and contact fields are for your own records. The supplier is what an
article and a destination both point at, so one supplier row is enough for
every shirt SPOD produces.

## 3. The SPOD destination

A destination is the supplier's delivery account. Under
`/admin/logistics/destinations` add one with:

| Field | Value |
| --- | --- |
| Supplier | the supplier from step 2 |
| Channel | `SPOD` (fixed after saving — a destination cannot change its channel later) |
| Label | any name, for example `Spreadconnect staging` |
| Enabled | on |
| Environment | `STAGING` first, `PRODUCTION` once the test order went through |
| Timeout (seconds) | 30 is a sane start; allowed 1…3600 |
| Access token | the API token from the partner's backoffice of the **same** environment |

There is no URL field: the base URL is derived from the environment in code
(`https://rest.spreadconnect-staging.app` and `https://rest.spreadconnect.app`).
Staging and production are separate installations with separate tokens and
separate data; a token of one is refused by the other.

The access token is write-only. It is never shown again, and leaving the field
empty on a later edit keeps the stored token.

A supplier may have **one** enabled SPOD destination at a time. To move from
staging to production, edit the existing destination (environment and token)
rather than creating a second one; the job history stays attached to it.

## 4. The webhook subscriptions

The partner reports shipments, cancellations, and open questions by calling
the shop. Subscribing is done against the partner's API, once per environment,
with the token from step 3 and the secret from step 1:

```sh
SPOD_BASE=https://rest.spreadconnect-staging.app   # or https://rest.spreadconnect.app
SPOD_TOKEN=…                                       # the destination's access token
SECRET=…                                           # production.spod.webhookSecret
for event in Shipment.sent Order.cancelled Order.needs-action; do
  curl -X POST "$SPOD_BASE/subscriptions" \
    -H "X-SPOD-ACCESS-TOKEN: $SPOD_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"eventType\":\"$event\",\"url\":\"https://<shop-host>/api/production/webhooks/spod/$SECRET\"}"
done
```

Two checks that are worth the minute:

- `curl -i https://<shop-host>/api/production/webhooks/spod/wrong-secret` must
  answer `403`; the same URL with the real secret must answer `202` with the
  body `[accepted]`.
- The shop must be reachable from the public internet. A shop behind a VPN or
  on `localhost` gets no shipments and no error.

For a local development setup there is no webhook: the job reaches
`prepared_at` and stays there until someone marks it shipped by hand on the
*Logistics* page.

## 5. The t-shirt article

Open `/admin/articles` and click **Add T-Shirt**. The editor has four tabs.

### Before you start: the partner's product ids

Every variant of a shirt names the printable product at the partner with three
numbers: a **product type id** (the garment, for example the standard unisex
tee), an **appearance id** (the colour of that garment), and a **size id**. The
appearance and size ids are specific to the product type; a size `M` of one
garment has a different id than `M` of another.

You get them from the partner's API with the token from step 3 — see the
[API reference](https://api.spreadconnect.app/docs/) for the exact shape:

```sh
curl -s "$SPOD_BASE/productTypes" -H "X-SPOD-ACCESS-TOKEN: $SPOD_TOKEN" \
  | jq '.items[] | {id, name, appearances: [.appearances[] | {id, name}], sizes: [.sizes[] | {id, name}]}'
```

Write down the product type you want, its appearance ids for the colours you
will offer, and its size ids. Staging and production ids may differ; take them
from the installation the destination points at.

### General

Name, short and long description, category (and optionally subcategory),
supplier (the one from step 2), and the *Active* flag. A shirt has no supplier
article number: the SPOD ids on the variants are the whole supplier mapping.

Leave *Active* off until the variants and the price are complete; activating
requires a category, a price, and at least one active variant.

### Print

- **Print aspect ratio**: the shape the customer's image is generated in.
  `1:1` is the square chest print; `16:9` is the wide format mugs use. Pick
  `1:1` for a shirt unless you know why not.
- **Print frame**: the rectangle on the shirt photo where the shop places the
  generated design in its preview, in percent of the photo (left, top, width,
  height). The calibrator draws the frame over the **default variant's example
  image** (or the first variant that has one), so upload that image in the
  *Variants* tab first, then come back and drag the numbers until the
  rectangle sits on the chest. Keep *Keep the print aspect ratio* on: the
  frame must have the same shape as the generated image, otherwise the preview
  is distorted; the **Fit height to …** button corrects the height for the current width.
  The frame may not leave the photo (`left + width ≤ 100`, `top + height ≤ 100`).

  The partner prints the design centred on the front (`MEDIUM_FRONT`); the
  frame only affects the shop's preview, so make it match what the printed
  shirt will look like.
- **Size chart**: one picture of the measurements per article (PNG, JPEG, or
  WebP, max 10 MB). Every variant of a shirt is measured by the same chart. The
  partner's backoffice has one per product type; a screenshot of it is fine.

### Variants

A shirt variant is one **colour in one size**, and all variants of one shirt
are the same garment, so the tab generates the matrix for you:

1. Enter the colours, one per line as `Name #rrggbb` (`Black #000000`), the
   sizes as a comma-separated list (`S, M, L, XL, XXL`), and the **SPOD product
   type id**.
2. Click **Generate … variants**. Colours × sizes becomes one row each. Rows
   that already exist keep their ids, picture, and lookups; a pair the new
   matrix no longer contains is deleted when the article is saved.
3. Per row, enter the **Appearance id** (same for every size of one colour) and
   the **Size id** (same for every colour of one size).
4. Upload an **example image** per variant (PNG, JPEG, or WebP, max 10 MB). This
   is the product photo the customer sees *and* the backdrop the design is
   composited onto in the shop's preview, so use a plain front view of the
   shirt in that colour. Use the same framing for every colour: one print
   frame applies to all variants. The partner's product-type mockup images are
   a good source.
5. Mark one variant as **Default** (the one the shop preselects) and leave
   *Active* on for the variants you sell.

A row without all three ids is refused on save; a shirt that cannot be ordered
from the printer is not a shirt variant. A variant's name (`Black / M`) is
composed from colour and size and cannot be typed.

### Price Calculation

The same price block as a mug: purchase VAT, sales VAT, and the gross sales
price in cents. The calculation fills the rest. An active shirt must have a
price.

Save the article, then switch *Active* on and save again once everything above
is in place.

## 6. The test order

Do one shirt end to end against staging before switching the destination to
production:

1. Open the shop, go to `/products?type=TSHIRT`, pick the shirt, and generate
   a design in the wizard. The image is generated in the article's aspect
   ratio and previewed inside the print frame — this is where a wrong frame
   shows.
2. Check out. A cart with a shirt **requires a phone number**; the partner
   needs it for the parcel.
3. Watch the job on `/admin/logistics`. Within a minute of payment the worker
   uploads the design, creates the order, and confirms it; the job then shows
   `prepared_at`. A job that stays open has a bounded error code in the
   database — the table in
   [SPOD fulfillment](backend/spod-fulfillment.md#the-bounded-error-codes)
   says what each one means. The two you will meet during set-up are
   `ITEM_WITHOUT_SPOD_PRODUCT` (a variant is missing an id) and `REFUSED` (the
   token does not belong to the destination's environment).
4. Simulate the shipment with the partner's staging **simulation endpoints**
   (see the *Simulation* section of the API reference; they exist only on
   staging) and check that the job turns *shipped* and the customer's shipping
   mail is enqueued. That proves the webhook URL and secret. Simulate
   `Order.cancelled` and `Order.needs-action` once too, and check that the
   ops alert arrives at `alertEmail` exactly once per job.

When that works, repeat step 0 for production (token, payment method), edit
the destination to `PRODUCTION` with the production token, register the
subscriptions against the production base URL (step 4 again), and replace
staging ids on the variants with production ids if they differ. Then order one
real shirt and judge the print on fabric before switching the article on for
customers: print images are lossy WebP, and only the garment proves the
quality.

## Adding a second shirt

Steps 0–4 are done. Create the article (step 5) with the new product type id
and its appearance and size ids, its own example images, and its own print
frame; the destination and the supplier are shared. Every shirt of one supplier
is one article — colours and sizes are variants, garments are articles.
