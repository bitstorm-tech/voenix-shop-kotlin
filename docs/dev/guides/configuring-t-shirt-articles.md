# Configuring a t-shirt article

This is the operator's manual for putting a t-shirt into the shop. It lists, in
order, what has to exist before a shirt can be sold and produced, and which
screen or file each piece is entered in. Nothing here is code: it is admin UI,
application configuration, and the partner's backoffice.

A mug is produced by a printer this shop pushes a PDF to; a shirt is produced
by the print-on-demand partner **Spreadconnect (SPOD)**, which this shop talks
to over a REST API. That is why a shirt needs more set-up than a mug: a supplier
with an API account and a webhook the partner can reach.

The one thing you never do is type a shirt into this admin. **The Spreadconnect
backoffice is the source of truth for the garment**
([ADR 0003](../../adr/0003-spod-backoffice-as-t-shirt-source.md)): you create
the article over there with its colours and its sizes, press *Sync from
Spreadconnect* here, and then decide the shop's half of it — price, category,
print frame, default variant, and whether it is active. There is no *Add
T-Shirt* button and no image upload; the pictures come from the partner too.

How the production side works once everything is configured is described in
[SPOD fulfillment](../backend/packages/spod-fulfillment.md); the data model of
the article itself is in the
[Article package guide](../backend/packages/article-package.md#t-shirts).

## The checklist

| Step | Where | Without it |
| --- | --- | --- |
| 0. Partner account, token, payment method | Spreadconnect backoffice | no token to enter, and orders the partner will not confirm |
| 1. `production.spod` configured | application configuration | the app refuses to start once a SPOD destination exists, and the admin UI refuses to create one |
| 2. Supplier created | `/admin/suppliers/new` | no destination and no article can name it |
| 3. SPOD destination created | `/admin/logistics/destinations` | the job is created but never submitted |
| 4. Webhook subscriptions registered | partner API (`curl`) | orders are produced, but the shop never learns they shipped |
| 5. Shirt created in the backoffice, synced, and completed here | Spreadconnect backoffice → `/admin/logistics/destinations` → `/admin/articles/tshirts` | nothing to sell |
| 6. Test order on staging | shop + `/admin/logistics` | you find the mistakes in step 3 and 5 with a paying customer |
| 7. Destination switched to `PRODUCTION` and re-synced | `/admin/logistics/destinations` | the shop keeps talking to the staging installation |

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
5. **Create the article you want to sell** in the backoffice: pick the garment
   (the product type), then every colour *and* every size you will offer. That
   choice is the article this shop will show — the sync writes exactly what is
   listed there, and switching a colour or a size off later is done over there
   too, never here. The mockup images and the size chart come from the same
   place; you upload nothing into this shop.

The partner's own docs are the [API reference](https://api.spreadconnect.app/docs/);
read its *Authentication*, *Subscriptions*, and *Simulation* sections once —
the last one is what makes step 6 possible without shipping a parcel.

## 1. The application configuration

The SPOD channel needs two values in the application configuration, both in the
`production.spod` block of [`application.yaml`](../../../backend/app/resources/application.yaml)
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

## 5. The shirt: backoffice first, then sync, then the shop's half

### 5a. Create the article in the Spreadconnect backoffice

Pick the garment and add **every colour and every size** you want to sell.
Whatever is listed there is what this shop will offer: the sync writes one
variant per colour × size combination, with the partner's three product ids,
the colour's hex value, the mockup image of that colour, and the size chart of
the product type. Nothing of that is typed or uploaded here any more — the ids
were the main source of wrong mappings, and now nobody copies them.

Two things to get right over there, because only the backoffice can fix them:

- **A colour without a usable front-view mockup, or with a colour value this
  shop cannot read, is synced as an inactive variant** with a warning. Nobody
  should order a garment whose colour the shop had to invent.
- **An article whose variants span more than one product type is skipped.** One
  shirt here is one garment in several colours and sizes.

### 5b. Press *Sync from Spreadconnect*

Open `/admin/logistics/destinations` and press **Sync from Spreadconnect** on
the SPOD destination of your supplier (step 3). The button sits on every SPOD
row, enabled destinations and disabled ones alike; you wait for the run, which
takes a few seconds for a small catalog.

The panel that appears is the run's report:

- how many articles were **created**, **updated**, **unchanged**,
  **deactivated**, or **failed** — a second press changes nothing and reports
  everything as *unchanged*, which is the normal answer;
- an expandable **warning list**: one line per thing the run degraded instead
  of failing on, with the article it belongs to (an unreadable colour value, a
  colour without a picture, a missing size chart, a title that had to be cut, a
  default variant that had to be replaced);
- a **failure alert** when the run could not read the whole listing. Then
  nothing was written and nothing was deactivated — that is on purpose, so a
  network hiccup cannot empty your shop. Fix the cause (usually the token or
  the environment) and press the button again.

Two answers instead of a report. Both arrive as a red toast, and the admin
surface words them itself rather than repeating the backend's sentence:

- *Only Spreadconnect destinations can be synced.* — you are on an SFTP row.
- *A sync is already running for this destination.* — a run of the same
  destination is still working.

### 5c. Complete the shop's half in the article editor

Open `/admin/articles/tshirts`. There is no *Add* action; the list shows every
synced shirt with a **Synced** column (when it was last read, and a *Missing at
Spreadconnect* badge for a shirt the partner no longer lists). Open the shirt.
The editor has four tabs.

#### General

Category and optional subcategory, the **default variant** (the colour and size
a customer sees first — the picker offers active variants only), and the
*Active* flag. The name, the descriptions, and the supplier are the partner's
and are shown on the *Spreadconnect* tab.

Leave *Active* off until the price and the frame are right. Activating requires
a category, a price, and an active default variant — and it is refused outright
for a shirt that is missing at Spreadconnect.

#### Print

- **Print aspect ratio**: the shape the customer's image is generated in.
  `1:1` is the square chest print; `16:9` is the wide format mugs use. Pick
  `1:1` for a shirt unless you know why not.
- **Print frame**: the rectangle on the shirt photo where the shop places the
  generated design in its preview, in percent of the photo (left, top, width,
  height). The calibrator draws the frame over the **default variant's synced
  mockup**, so pick the default variant on the *General* tab first, then drag
  the numbers until the rectangle sits on the chest. Keep *Keep the print
  aspect ratio* on: the frame must have the same shape as the generated image,
  otherwise the preview is distorted; the **Fit height to …** button corrects
  the height for the current width. The frame may not leave the photo
  (`left + width ≤ 100`, `top + height ≤ 100`).

  A newly synced shirt starts from a centred default frame. The partner's API
  offers no geometry relative to the mockup, so this one calibration is
  unavoidable — and it is worth doing properly: the partner prints the design
  centred on the front (`MEDIUM_FRONT`), and the frame only affects the shop's
  preview, so make it match what the printed shirt will look like.

#### Spreadconnect

Read-only, and the whole point of the tab: what the last run wrote. The name
and both descriptions, when the shirt was last synced, which installation and
which backoffice article it is, the table of active variants (mockup, colour
with its swatch, size, the partner's variant id, the SKU, and the three product
ids), the inactive variants behind a *Show … inactive variants* button, and the
size-chart image. Everything here is overwritten by the next run — change it in
the backoffice, not here.

#### Price Calculation

The same price block as a mug: purchase VAT, sales VAT, and the gross sales
price in cents. The calculation fills the rest. An active shirt must have a
price. The partner's own prices are deliberately not stored: what the shop
charges is the shop's decision. The optional discount on that price is part of
it and therefore shop-owned as well: a sale you configure here survives every
sync run, because a run never touches the price.

Save, then switch *Active* on and save again once everything above is in place.

### What changes in the backoffice, and what it does here

| You do this over there | The next sync does this here |
| --- | --- |
| rename the article, edit its description | overwrites name and descriptions |
| add a colour or a size | adds the variants, active as long as the colour has a readable value and a usable mockup. The article itself is never switched on by a run |
| remove a colour or a size | those variants go inactive; their rows stay, so old orders still resolve. If the default variant was among them, another active one takes its place |
| replace a mockup image | downloads the new picture and reports `EXAMPLE_IMAGE_REPLACED` |
| remove the whole article | the shirt is deactivated and marked *Missing at Spreadconnect*; it is never deleted |
| put the article back | the marker is cleared, but the shirt **stays inactive** until you switch it on |

A rename of a colour has one side effect worth knowing: production jobs of that
colour that are already waiting are quarantined by the name tripwire with
`SPOD_MAPPING_CHANGED`. That is a known false alarm with a fixed resolution —
see
[the runbook](../backend/packages/spod-fulfillment.md#spod_mapping_changed-after-a-rename-in-the-backoffice).

The one thing you do **here** and not over there is retiring a shirt for good:
*Delete* on the article removes it from the shop. Do it only for a shirt that
will not come back — everything else is a switch in the backoffice.

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
   [SPOD fulfillment](../backend/packages/spod-fulfillment.md#the-bounded-error-codes)
   says what each one means. The one you will meet during set-up is `REFUSED`:
   the token does not belong to the destination's environment.
4. Simulate the shipment with the partner's staging **simulation endpoints**
   (see the *Simulation* section of the API reference; they exist only on
   staging) and check that the job turns *shipped* and the customer's shipping
   mail is enqueued. That proves the webhook URL and secret. Simulate
   `Order.cancelled` and `Order.needs-action` once too, and check that the
   ops alert arrives at `alertEmail` exactly once per job.

## 7. Moving from staging to production

The two installations are separate shops with separate tokens, separate data,
and **unrelated article ids**: backoffice article `12` on staging has nothing to
do with backoffice article `12` on production. The sync knows that — a synced
shirt is identified by (destination, environment, backoffice article id), so
switching the environment of a destination cannot make a production article
inherit a staging article's price, category, or frame (ADR 0003, decision 4).

What that means in practice: **the staging shirts do not travel.** After the
switch they are shirts of an installation this destination no longer talks to,
so the next sync marks them *Missing at Spreadconnect* and deactivates them,
and the production catalog arrives as newly created articles that you complete
once more (5c). That is the intended behaviour, not an accident — the price,
the category, and the frame are cheap to re-enter, while silently merging two
installations' catalogs would be a shop selling the wrong garment.

The order:

1. Repeat step 0 for the production account: token, payment method, and the
   articles themselves — a shirt created on staging exists only there.
2. Edit the destination (step 3) to `environment: PRODUCTION` with the
   production token. The supplier and the channel cannot be changed; the
   environment and the token can, and the job history stays attached to the
   row.
3. Register the webhook subscriptions against the production base URL (step 4
   again).
4. Press **Sync from Spreadconnect** once. Expect the staging shirts in
   *deactivated* and the production ones in *created*.
5. Complete each production shirt (5c) and delete the deactivated staging ones
   when you are sure they will not come back.
6. Order one real shirt and judge the print on fabric before switching the
   article on for customers: print images are lossy WebP, and only the garment
   proves the quality.

## Adding a second shirt

Steps 0–4 are done. Create the garment in the backoffice (5a), press *Sync from
Spreadconnect* (5b) — every shirt of that destination is reconciled in the same
run, and the ones you already completed stay *unchanged* — and complete the new
one (5c). The destination and the supplier are shared. Every shirt of one
supplier is one article: colours and sizes are variants, garments are articles.
