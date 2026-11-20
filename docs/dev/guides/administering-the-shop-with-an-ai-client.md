# Administering the shop with an AI client

An AI client such as Claude Code, Codex, or Cursor can administer the shop's
catalog and master data the way an admin does in the dashboard: countries,
prices and VAT, promotions, suppliers, prompts, articles and their categories,
the operations findings, and orphaned images. It connects to the shop's MCP
endpoint `/api/mcp` with an access token. How the endpoint works is in the
[MCP package guide](../backend/packages/mcp-package.md).

## 1. Create a token

1. Sign in as an admin and open **Access tokens** in the admin navigation
   (`/admin/access-tokens`).
2. Enter a name that says where the token will live ("Claude Code on Joe's
   laptop") and create it.
3. The page shows the token **once**, with the endpoint URL and a ready
   command. Copy it now: it cannot be shown again, and leaving or reloading
   the page drops it. The shop stores only its hash.

A token acts as the admin who created it, with that admin's roles at the
moment of each request. It has no expiry.

## 2. Connect a client

Claude Code:

```sh
claude mcp add --transport http voenix-admin https://<shop>/api/mcp \
  --header "Authorization: Bearer <token>"
```

Any other MCP client: a Streamable HTTP server at `https://<shop>/api/mcp`
with the header `Authorization: Bearer <token>`. In development the URL is the
Vite server's (`http://localhost:5173/api/mcp`), which forwards to the
backend.

## 3. The three tools

| Tool | What it does |
| --- | --- |
| `admin_endpoints` | Lists every endpoint the token may call; with a `path`, also the JSON Schema of each body. |
| `admin_read` | Sends a `GET` to an admin endpoint and answers the status and the body. |
| `admin_write` | Sends a `POST`, `PUT`, `PATCH`, or `DELETE` with a JSON body. Clients mark it as destructive and can ask before each call. |

Every rule of the dashboard holds: the admin routes validate and decide, and a
refused write answers the same field errors the dashboard shows. File uploads
(`multipart` endpoints) and binary answers (images, PDFs) do not go through
MCP; a script can call those endpoints over HTTP with the same token.

## What the AI cannot reach

The token opens only the admin routes listed in the
[authentication guide](../backend/conventions/authentication-and-authorization.md#which-admin-routes-a-token-opens).
It never reaches:

- orders and production (customer names, e-mail and postal addresses), the
  production destinations and their credentials;
- supplier logins and the access tokens themselves (a token cannot create or
  revoke a token);
- the background removal, which costs money per call;
- any customer-facing or account route, including the admin's own account.

## The risk you accept

The AI reads text the team did not write: the partner's catalog text of SPOD
articles. Such text could try to instruct the AI ("prompt injection"), and the
AI can write to every open route. The server tells the client that returned
data is data, never an instruction, and marks `admin_write` as destructive,
but it cannot enforce either. Let the client ask before each write when it
works on SPOD articles, and read what it is about to change.

## 4. Revoke a token

Open **Access tokens** and revoke the token. The very next request with it
answers `401`. Every admin may revoke every admin's token; the list shows the
owner and when each token was last used. Removing a user's `ADMIN` role, or
deleting the user, takes effect on the next request as well.
