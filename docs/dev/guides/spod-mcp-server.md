# SPOD / Spreadconnect MCP Server (lokales Dev-Tool)

[`spod-mcp/`](../../../spod-mcp) ist ein eigenständiger lokaler Stdio-MCP-Server für direkte Abfragen und Tests gegen die Spreadconnect-(SPOD)-REST-API (`STAGING` und `PRODUCTION`). Er läuft getrennt vom Backend-Server und greift nicht auf die Shop-Datenbank oder `/api/mcp` zu.

## Einrichtung

Einmalig Abhängigkeiten installieren:

```sh
cd spod-mcp && bun install
```

## Tools

| Tool | Methode | Beschreibung |
| --- | --- | --- |
| `spod_read` | `GET` | Ruft einen relativen SPOD-Pfad (inkl. Query-String) ab und gibt HTTP-Status sowie den rohen Response-Body (JSON pretty-printed) zurück. |
| `spod_write` | `POST`, `PUT`, `PATCH`, `DELETE` | Sendet schreibende Anfragen mit optionalem JSON-Body. Auf `STAGING` standardmäßig aktiv; auf `PRODUCTION` ausgeblendet, solange `SPOD_ALLOW_PRODUCTION_WRITE=true` nicht explizit gesetzt ist. |

### Schutzmechanismen

- **Feste Ziel-URLs:** `STAGING` spricht ausschließlich `https://rest.spod-staging.com`, `PRODUCTION` ausschließlich `https://rest.spod.com` an; Pfade, die diese Origin verlassen, werden abgewiesen.
- **Keine Redirects:** `redirect: 'manual'`, damit der `X-SPOD-ACCESS-TOKEN`-Header niemals weitergeleitet wird.
- **Rate-Limit-Pacing:** Maximal ein Request alle `1050 ms` (SPOD-Limit: 60 Requests/Minute pro Account).
- **Keine Token-Leaks:** Der Token erscheint weder in Tool-Ausgaben noch in Fehlertexten; Binärantworten werden nicht durchgeschleust und Bodies über 2 MiB abgebrochen.

## Umgebungsvariablen

| Variable | Pflicht | Default | Bedeutung |
| --- | --- | --- | --- |
| `SPOD_ACCESS_TOKEN` | ja | – | API-Token aus dem Spreadconnect-Backoffice (`X-SPOD-ACCESS-TOKEN`). |
| `SPOD_ENVIRONMENT` | nein | `STAGING` | `STAGING` oder `PRODUCTION`. |
| `SPOD_ALLOW_PRODUCTION_WRITE` | nein | `false` | Nur mit `true` wird `spod_write` auf `PRODUCTION` registriert. |
| `SPOD_TIMEOUT_SECONDS` | nein | `30` | Timeout pro HTTP-Request (`1`–`120`). |

## Client verbinden

### Antigravity CLI (`agy`)

Für **Staging** (Read + Write):

```sh
agy mcp add \
  --env SPOD_ENVIRONMENT=STAGING \
  --env SPOD_ACCESS_TOKEN=<staging-token> \
  spod-staging bun /absoluter/pfad/zum/repo/spod-mcp/src/index.ts
```

Für **Production** (Read-only):

```sh
agy mcp add \
  --env SPOD_ENVIRONMENT=PRODUCTION \
  --env SPOD_ACCESS_TOKEN=<prod-token> \
  spod-prod bun /absoluter/pfad/zum/repo/spod-mcp/src/index.ts
```

### Claude Code

Für **Staging** (Read + Write):

```sh
claude mcp add --transport stdio spod-staging \
  --env SPOD_ENVIRONMENT=STAGING \
  --env SPOD_ACCESS_TOKEN=<staging-token> \
  -- bun /absoluter/pfad/zum/repo/spod-mcp/src/index.ts
```

Für **Production** (Read-only):

```sh
claude mcp add --transport stdio spod-prod \
  --env SPOD_ENVIRONMENT=PRODUCTION \
  --env SPOD_ACCESS_TOKEN=<prod-token> \
  -- bun /absoluter/pfad/zum/repo/spod-mcp/src/index.ts
```

### Andere MCP-Clients (JSON-Konfiguration)

```json
{
  "mcpServers": {
    "spod-staging": {
      "command": "bun",
      "args": ["/absoluter/pfad/zum/repo/spod-mcp/src/index.ts"],
      "env": {
        "SPOD_ENVIRONMENT": "STAGING",
        "SPOD_ACCESS_TOKEN": "<staging-token>"
      }
    }
  }
}
```

## Typische Abfragen

- `GET /authentication` – prüft Token, Merchant-ID und Point of Sale
- `GET /articles?limit=10&offset=0` – Backoffice-Artikel inspizieren
- `GET /productTypes/2973`, `/views`, `/size-chart` – Produkttyp-Metadaten, Druckflächen, Größentabellen
- `GET /subscriptions` – registrierte Webhooks prüfen
- `POST /orders/{id}/simulate/shipment-sent` (bzw. `order-cancelled`, `order-processed`) – Webhook-Event auf Staging simulieren

## Tests

```sh
cd spod-mcp && bun run check
```
