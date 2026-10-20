# Configuring a SPOD article

This is the operator's manual for putting a print-on-demand product into the
shop — a t-shirt, a tote bag, or any other product the partner offers. It
lists, in order, what has to exist before such an article can be sold and
produced, and which screen or file each piece is entered in. Nothing here is
code: it is admin UI, application configuration, and the partner's backoffice.

A mug is produced by a printer this shop pushes a PDF to; a *SPOD article* is
produced by the print-on-demand partner **Spreadconnect (SPOD)**, which this
shop talks to over a REST API. That is why a SPOD article needs more set-up
than a mug: a supplier with an API account and a webhook the partner can reach.
Once that set-up exists, a new product costs no code at all: **create it in the
backoffice, press *Sync*, set price and category, activate.** The article type
is `SPOD` for every one of them; whether it is a shirt or a bag is data — the
partner's product type name, which the shop shows as a subtitle, and the
category you file it under.

The one thing you never do is type a SPOD article into this admin. **The
Spreadconnect backoffice is the source of truth for the product**
([SPOD sync decision 1](https://github.com/bitstorm-tech/voenix-shop-kotlin/blob/kotlin-backend/docs/dev/backend/packages/article-package.md#the-sync-decisions)):
you create the article over there with its colours and its sizes, press *Sync
from Spreadconnect* here, and then decide the shop's half of it — price,
category, default variant, and whether it is active. There is no *Add* button,
no image upload, and no print frame to type: the pictures come from the
partner, and the frame the customer's design is printed into is measured from
the partner's own previews by every sync run.

This guide uses two examples throughout: a **tee** (a men's t-shirt in several
colours and sizes) and the Stanley/Stella **tote bag** (product type 2973, eight
colours, one size). Both go through exactly the same steps.

How the production side works once everything is configured is described in
[SPOD fulfillment](https://github.com/bitstorm-tech/voenix-shop-kotlin/blob/kotlin-backend/docs/dev/backend/packages/spod-fulfillment.md); the data model of
the article itself is in the
[Article package guide](https://github.com/bitstorm-tech/voenix-shop-kotlin/blob/kotlin-backend/docs/dev/backend/packages/article-package.md#spod-articles).

## The checklist

| Step | Where | Without it |
| --- | --- | --- |
| 0. Partner account, token, payment method | Spreadconnect backoffice | no token to enter, and orders the partner will not confirm |
| 1. `production.spod` configured | application configuration | the app refuses to start once a SPOD destination exists, and the admin UI refuses to create one |
| 2. Supplier created | `/admin/suppliers/new` | no destination and no article can name it |
| 3. SPOD destination created | `/admin/logistics/destinations` | the job is created but never submitted |
| 4. Webhook subscriptions registered | partner API (`curl`) | orders are produced, but the shop never learns they shipped |
| 5. Article created in the backoffice, synced, and completed here | Spreadconnect backoffice → `/admin/logistics/destinations` → `/admin/articles/spod` | nothing to sell |
| 6. Test order on staging | shop + `/admin/logistics` | you find the mistakes in step 3 and 5 with a paying customer |
| 7. Destination switched to `PRODUCTION` and re-synced | `/admin/logistics/destinations` | the shop keeps talking to the staging installation |

Steps 0–4 are done **once per supplier** (and once per partner installation,
staging and production); step 5 is repeated for every product.

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
   country gate (SPOD fulfillment decision 7): an order into a country the partner
   does not ship to is refused at creation, reaches you as an
   `ORDER_CREATE_REJECTED` alert, and is refunded by hand. Know the list.
5. **Create the article you want to sell** in the backoffice: pick the product
   type (the tee, the tote bag, …), then every colour *and* every size you will
   offer. That choice is the article this shop will show — the sync writes
   exactly what is listed there, and switching a colour or a size off later is
   done over there too, never here. The product photos, the mockups, and the
   size chart with its measurements come from the same place; you upload
   nothing into this shop.

The partner's own docs are the [API reference](https://api.spreadconnect.app/docs/);
read its *Authentication*, *Subscriptions*, and *Simulation* sections once —
the last one is what makes step 6 possible without shipping a parcel.

## 1. The application configuration

The SPOD channel needs two environment variables of the backend (see
[Running the development server](../backend/getting-started/running-the-development-server.md)):

```sh
PRODUCTION_SPOD_WEBHOOK_SECRET=   # at least 32 characters
OPERATIONS_ALERT_EMAIL=
```

- `PRODUCTION_SPOD_WEBHOOK_SECRET` is the last path segment of the callback URL the partner
  calls when a parcel ships (`/api/production/webhooks/spod/{secret}`). Generate
  a random string of at least 32 characters, for example with
  `openssl rand -hex 32`, and keep it: you need the same value in step 4.
- `OPERATIONS_ALERT_EMAIL` is the shared mailbox that receives the ops alert
  when a job needs a human (a cancelled order, a quarantined job, an order the
  partner refuses), and the operations alert about every stalled order. Use a
  mailbox someone actually reads.

The application checks at startup: the secret without the address fails, and no
secret while a SPOD destination exists in the database fails too. The admin UI enforces the same rule from the other side and refuses
to create a SPOD destination while the block is empty, so do this step first.
Restart the application after changing the configuration.

## 2. The supplier

Create the supplier under `/admin/suppliers/new`. Only the name is required; the
address and contact fields are for your own records. The supplier is what an
article and a destination both point at, so one supplier row is enough for
every article SPOD produces.

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
(`https://rest.spod-staging.com` and `https://rest.spod.com`).
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
SPOD_BASE=https://rest.spod-staging.com   # or https://rest.spod.com
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

## 5. The article: backoffice first, then sync, then the shop's half

### 5a. Create the article in the Spreadconnect backoffice

Pick the product type and add **every colour and every size** you want to sell
— the tee in black and white, S to XL; the tote bag in its eight colours, in
its one size. Whatever is listed there is what this shop will offer: the sync
writes one colour per appearance, with its hex value, the product photos the
backoffice lists for it (the *listing images*), and the blank front picture of
the product type (the *mockup image*), plus one variant per colour × size
combination with the partner's three product ids, and the size chart of the
product type: its picture with the letters A, B, C, and per size the value of
each letter in millimetres. Only the sizes the article sells are kept; what a
letter measures is shown only by the picture. Nothing of that is typed or
uploaded here any more — the ids were the main source of wrong mappings, and
now nobody copies them.

The order of the colours and of the photos is the backoffice's order. A
product card starts with the first listing image of its colour and lets the
customer leaf through all of them, so choose and sort the photos over there.

Three things to get right over there, because only the backoffice can fix them:

- **A colour is only sellable with a mockup and at least one listing image,
  and with a colour value this shop can read.** Otherwise it is synced with its
  variants inactive and a warning. Nobody should order a product whose colour
  the shop had to invent or cannot show. The staging installation is known to
  list pictures its CDN answers with `404` — such an article arrives with every
  variant inactive, and the next run after the pictures exist fills them in.
- **An article whose variants span more than one product type is skipped.** One
  article here is one product in several colours and sizes.
- **The product type needs a front view with at least one front hotspot** —
  the print positions of the front, such as `MEDIUM_FRONT` or `FULL_FRONT`. The
  sync measures the print frame on them (see
  [Reading the sync report](#reading-the-sync-report-hotspot-and-frame)); a
  product type without one cannot be sold through this shop.

### 5b. Press *Sync from Spreadconnect*

Open `/admin/logistics/destinations` and press **Sync from Spreadconnect** on
the SPOD destination of your supplier (step 3). The button sits on every SPOD
row, enabled destinations and disabled ones alike; you wait for the run, which
takes a few seconds per product type (see [the rate budget](#the-rate-budget)).

The panel that appears is the run's report:

- how many articles were **created**, **updated**, **unchanged**,
  **deactivated**, or **failed** — a second press changes nothing and reports
  everything as *unchanged*, which is the normal answer;
- one line per created or updated article with the **hotspot** the run chose
  for it, or *none (print frame not measured)*;
- a red alert listing the articles whose **print frame could not be measured**
  — they cannot be activated (see the next section);
- an expandable **warning list**: one line per thing the run degraded instead
  of failing on, with the article it belongs to (an unreadable colour value, a
  colour without a picture or a mockup, a failed calibration, a missing size
  chart, a title that had to be cut, a default variant that had to be
  replaced) — and next to every **failed** article the reason nothing was
  written for it (a calibration the partner did not answer, two colours of
  one name, an id too long to store);
- a **failure alert** when the run could not read the whole listing. Then
  nothing was written and nothing was deactivated — that is on purpose, so a
  network hiccup cannot empty your shop. Fix the cause (usually the token or
  the environment) and press the button again.

Two answers instead of a report. Both arrive as a red toast, and the admin
surface words them itself rather than repeating the backend's sentence:

- *Only Spreadconnect destinations can be synced.* — you are on an SFTP row.
- *A sync is already running for this destination.* — a run of the same
  destination is still working.

### Reading the sync report: hotspot and frame

The **print frame** is the rectangle on the mockup image where the customer's
design sits: the editor in the shop shows the design inside it, and the partner
prints it exactly there. Nobody types it. Every run measures it per product
type — this is the *calibration* (SPOD sync decision 7):

1. the run uploads one magenta square to the partner as a design;
2. it asks the product type for its front hotspots and which of them accept
   that design;
3. it lets the partner render a preview of the square on every one of them and
   finds the magenta box in each picture;
4. the **hotspot** with the largest box wins, and that hotspot and its box are
   written into every article of the product type.

The result shows in two places: the report line names the hotspot, and the
*Print* section of the article (5c) draws the box on the mockup with its four
numbers. The numbers are percentages of the mockup picture: *Left* and *Top*
are the distance of the box's upper left corner from the left and the top edge,
*Width* and *Height* its size. A tee measured at `35.6 / 18.9 / 28.4 / 28.4 %`
prints a square that starts a good third from the left and a fifth from the
top, and covers a bit more than a quarter of the picture in each direction.
The box is the same for every colour of the product type, and it is always a
square: a SPOD article is always printed `1:1`, and the customer's image is
generated in that shape.

Every run measures again — nothing is remembered between runs. If the partner
changes its mockup or its hotspots, the next run simply measures the new ones.
An order that was placed before keeps the hotspot it was placed with: the
order line stores it, and production prints at exactly that hotspot.

**When the measurement fails.** A preview that shows no clean magenta rectangle
is not trusted. The report then carries the warning `CALIBRATION_FAILED`, the
red alert names the article, and the article is written **without** a frame
and a hotspot and is switched off. Its *Print* section shows *The last sync
could not measure the print frame*, and activating it is refused. The warning's
detail says why, per hotspot that was tried:

| Detail | Meaning |
| --- | --- |
| `NO_FRONT_VIEW` | the product type has no view named `FRONT` |
| `NO_PRINTABLE_HOTSPOT` | none of the front hotspots accepts the design |
| `NO_RECTANGLE` | the preview showed no magenta box, or one too small to be a print area |
| `LOW_FILL` | the box has holes or a ragged edge — something covers part of the design |
| `UNREADABLE_IMAGE` | the preview picture could not be read |
| `PROVIDER_ANSWER_UNREADABLE` | the partner rendered the preview but answered no front picture for it |

What to do: open the product type in the Spreadconnect backoffice and check
its **front hotspots** — that the product has a front view and that the design
fits a front position. Then press *Sync from Spreadconnect* again. There is
nothing to set in this shop: the frame comes from the partner or not at all.

**When the partner does not answer.** `CALIBRATION_UNAVAILABLE` is a different
thing from `CALIBRATION_FAILED`: the product type was not measured because the
partner could not be reached — the views, the design upload, the hotspot check,
or every preview was limited, not answered, or refused. That says nothing about
the product type, so the run does not touch the article at all: it is counted
as **failed**, nothing of it is written or downloaded, and the article stays
exactly as the last good run left it — still on sale if it was. The detail
names the call and the error, for example `designs/upload: PROVIDER_UNAVAILABLE`
or `FULL_FRONT previews: RATE_LIMITED`. What to do: nothing in the backoffice;
just sync again a little later.

In short: `CALIBRATION_UNAVAILABLE` means *the partner was not reachable, sync
again later*; `CALIBRATION_FAILED` means *this product type cannot be measured,
fix its front hotspots in the backoffice*.

### The rate budget

The partner allows 60 API requests per minute for the whole account, and the
shop paces every call to stay below that. A run costs, **per product type** it
meets, two calls plus one preview per accepted front hotspot (at most five), and
once per run one design upload; the picture downloads do not count. Twenty tees
of one product type therefore cost as much calibration as one tee. Each paced
call takes a little over a second, so ten different product types with two
front hotspots each add about forty seconds to a run. The run is synchronous:
the button waits until it is done.

### 5c. Complete the shop's half in the article editor

Open `/admin/articles/spod` (*SPOD articles* in the navigation). There is no
*Add* action; the list shows every synced article with a **Synced** column
(when it was last read, and a *Missing at Spreadconnect* badge for an article
the partner no longer lists). Open the article. The editor shows everything on
one page, in four sections stacked one below the other: *General*, *Price*,
*Print*, and *Spreadconnect*. *Save* sits in a bar that stays at the bottom of
the screen while you scroll, and it saves all sections at once. If something
stops the save, every problem is marked at the same time and the page scrolls
to the first one.

#### General

The article's name with the partner's product type name below it (*Men's
T-Shirt*, *Tote Bag*), category and optional subcategory, the **default
variant** (the colour and size a customer sees first — the picker offers active
variants only), and the *Active* flag. The descriptions and the supplier are the
partner's and are shown in the *Spreadconnect* section.

Leave *Active* off until the price is right. Activating requires a category, a
price, an active default variant, and a measured print frame — and it is
refused outright for an article that is missing at Spreadconnect.

#### Price

The same price block as a mug: purchase VAT, sales VAT, and the gross sales
price in cents. The calculation fills the rest. An active article must have a
price. The partner's own prices are deliberately not stored: what the shop
charges is the shop's decision. The optional discount on that price is part of
it and therefore shop-owned as well: a price discount you configure here
survives every sync run, because a run never touches the price.

#### Print

Read-only. It shows what the last run measured: the hotspot, the four
percentages, and the frame drawn on the mockup of the default variant's colour
(see [Reading the sync report](#reading-the-sync-report-hotspot-and-frame)).
Look at it before you activate: the box should sit where the motif belongs on
the product. If it does not, the fix is in the backoffice, not here. When the
last run could not measure the frame, the section shows a red alert instead and
the article cannot be activated.

#### Spreadconnect

Read-only — the section has a grey background and a *Read-only* badge — and
the whole point of it: what the last run wrote. The name and both descriptions,
when the article was last synced, which installation and which backoffice
article it is, the table of active variants (listing image, colour with its
swatch, size, the partner's variant id, the SKU, and the three product ids), the
inactive variants behind a *Show … inactive variants* button, and the
size-chart image. Everything here is overwritten by the next run — change it in
the backoffice, not here.

Save, then switch *Active* on and save again once everything above is in place.

### What the shop shows

The storefront adapts to the article instead of needing settings: colour
swatches appear only when there is more than one colour, size buttons and the
size chart only when there is more than one size. The tote bag therefore shows
eight swatches and no size picker; the tee shows both. The editor lays the
customer's design on the mockup of the chosen colour, inside the measured
frame.

### What changes in the backoffice, and what it does here

| You do this over there | The next sync does this here |
| --- | --- |
| rename the article, edit its description | overwrites name and descriptions |
| add a colour or a size | adds the variants, active as long as the colour has a readable value, a mockup, and a listing image. The article itself is never switched on by a run |
| remove a colour or a size | those variants go inactive; their rows stay, so old orders still resolve. If the default variant was among them, another active one takes its place |
| replace or reorder the photos of a colour | downloads the new pictures; reports `EXAMPLE_IMAGE_REPLACED` when the picture the shop shows the article with changed |
| change the product type's hotspots or mockup | the run measures again and writes the new hotspot and frame — or switches the article off with `CALIBRATION_FAILED` |
| remove the whole article | the article is deactivated and marked *Missing at Spreadconnect*; it is never deleted |
| put the article back | the marker is cleared, but the article **stays inactive** until you switch it on |

A rename of a colour has one side effect worth knowing: production jobs of that
colour that are already waiting are quarantined by the name tripwire with
`SPOD_MAPPING_CHANGED`. That is a known false alarm with a fixed resolution —
see
[the runbook](https://github.com/bitstorm-tech/voenix-shop-kotlin/blob/kotlin-backend/docs/dev/backend/packages/spod-fulfillment.md#spod_mapping_changed-after-a-rename-in-the-backoffice).
A hotspot change has one too: an order placed before it is still sent with the
old hotspot, and if the partner no longer accepts that one, the job stops with
`ORDER_CREATE_REJECTED` — see
[the runbook](https://github.com/bitstorm-tech/voenix-shop-kotlin/blob/kotlin-backend/docs/dev/backend/packages/spod-fulfillment.md#order_create_rejected-after-a-hotspot-change).

The one thing you do **here** and not over there is retiring an article for
good: *Delete* on the article removes it from the shop. Do it only for an
article that will not come back — everything else is a switch in the
backoffice.

## 6. The test order

Do one article end to end against staging before switching the destination
to production:

1. Open the shop, go to `/products?type=SPOD`, pick the article, and generate
   a design in the wizard. The image is generated square and previewed inside
   the measured print frame on the mockup of the chosen colour.
2. Check out. A cart with a SPOD article **requires a phone number**; the
   partner needs it for the parcel.
3. Watch the job on `/admin/logistics`. Within a minute of payment the worker
   uploads the design, creates the order, and confirms it; the job then shows
   `prepared_at`. A job that stays open has a bounded error code in the
   database — the table in
   [SPOD fulfillment](https://github.com/bitstorm-tech/voenix-shop-kotlin/blob/kotlin-backend/docs/dev/backend/packages/spod-fulfillment.md#the-bounded-error-codes)
   says what each one means. The one you will meet during set-up is `REFUSED`:
   the token does not belong to the destination's environment.
4. Simulate the shipment with the partner's staging **simulation endpoints**
   (see the *Simulation* section of the API reference; they exist only on
   staging) and check that the job turns *shipped* and the customer's shipping
   mail is enqueued. That proves the webhook URL and secret. Simulate
   `Order.cancelled` and `Order.needs-action` once too, and check that the
   ops alert arrives at `OPERATIONS_ALERT_EMAIL` exactly once per job.

## 7. Moving from staging to production

The two installations are separate shops with separate tokens, separate data,
and **unrelated article ids**: backoffice article `12` on staging has nothing to
do with backoffice article `12` on production. The sync knows that — a synced
article is identified by (destination, environment, backoffice article id), so
switching the environment of a destination cannot make a production article
inherit a staging article's price or category (SPOD sync decision 4).

What that means in practice: **the staging articles do not travel.** After the
switch they are articles of an installation this destination no longer talks
to, so the next sync marks them *Missing at Spreadconnect* and deactivates
them, and the production catalog arrives as newly created articles that you
complete once more (5c). That is the intended behaviour, not an accident — the
price and the category are cheap to re-enter, while silently merging two
installations' catalogs would be a shop selling the wrong product. The frame
is measured again on production anyway.

The order:

1. Repeat step 0 for the production account: token, payment method, and the
   articles themselves — an article created on staging exists only there.
2. Edit the destination (step 3) to `environment: PRODUCTION` with the
   production token. The supplier and the channel cannot be changed; the
   environment and the token can, and the job history stays attached to the
   row.
3. Register the webhook subscriptions against the production base URL (step 4
   again).
4. Press **Sync from Spreadconnect** once. Expect the staging articles in
   *deactivated* and the production ones in *created*, each with its hotspot.
5. Complete each production article (5c) and delete the deactivated staging
   ones when you are sure they will not come back.
6. Order one real article of each product type and judge the print before
   switching it on for customers: print images are lossy WebP, and only the
   real product proves the quality.

## Adding another product

Steps 0–4 are done. Say the tee is on sale and the tote bag comes next: create
the tote bag in the backoffice (5a), press *Sync from Spreadconnect* (5b) —
every article of that destination is reconciled in the same run, and the ones
you already completed stay *unchanged* — check its hotspot and frame in the
report, and complete it (5c): a price, a category, activate. The destination
and the supplier are shared, and no code changes. Every product of one supplier
is one article: colours and sizes are variants, products are articles.
