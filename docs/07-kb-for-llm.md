# 07 — LLM Knowledge Base (IrisGUI Endpoints · Databases · irispy-client API)

This document is not human-facing prose. It is a complete machine-oriented reference
that lets an LLM generate working KakaoTalk bot code without reading any other doc.
Version pinning: IrisGUI 0.0.9, irispy-client 0.2.6 (PyPI), import name `iris`.

## 0. How to use this file

- Default choice when writing a bot: `from iris.bot import Bot`, register callbacks
  with `on_event`, call `bot.run()`.
- Use raw HTTP (`POST /reply`, WebSocket frames) only for languages without the
  Python client.
- The DB schema in §5 is only reachable through `POST /query`. No client can open
  the database files directly.
- Every `reply` sends to a real KakaoTalk account. Never broadcast replies to a
  room for testing outside the designated test rooms.
- The type/origin tables in §4 are the single source of truth for message decisions.

## 1. Connection contract

- Base URL `http://<device-ip>:<port>`, WebSocket `ws://<device-ip>:<port>/ws`.
  Default port 3000 (`bot_http_port`; read it with `GET /config`).
- All request bodies are parsed with `ignoreUnknownKeys=true` — extra fields are
  silently dropped, on both directions.
- Response envelopes:
  - ACK: `{"success": bool, "message": str}`
  - Read-style: `{"payload": ..., "error": null|string}`
  - HTTP 500 + `{"success": false, "message": "<exception text>"}` means an exception.
- Mode availability:
  - ROOT_ADB · HAYUL: everything below.
  - NON_ROOT: only `/ws` and `/reply`. Any other path does not exist.
- No auth header or token on `/ws` or HTTP. Only network reachability (firewall,
  port forwarding) matters.
- irispy-client `Bot(url)` argument rule: `http://`/`ws://` prefixes are stripped,
  but the remainder is validated as `IP:PORT` with a dotted-quad IP. A domain name
  or a bare IP raises `ValueError`. Always pass like `192.168.0.50:3000`.

## 2. HTTP endpoints

### GET /config

Response:
```json
{"bot_name": str, "bot_http_port": int, "web_server_endpoint": str,
 "db_polling_rate": int_ms, "message_send_rate": int_ms, "bot_id": int}
```
`broadcast_types: [str]` appears only when a type filter was set. `bot_id == 0`
means DB polling is stopped (bot id was never detected).

### POST /config/{name}

| name | body | applies | validation |
|---|---|---|---|
| `endpoint` | `{"endpoint": str\|null}` | immediate | null → `""` (disable webEndpoint) |
| `botname` | `{"botname": str}` | immediate | empty → 500 |
| `dbrate` | `{"rate": int}` | immediate | polling interval, ms |
| `sendrate` | `{"rate": int}` | immediate | send interval, ms |
| `botport` | `{"port": int}` | after restart | 1..65535 |
| `types` | `{"types": [str]}` | immediate | filter tokens from §4; `[]` disables the filter |
| `extension` | `{"enable": bool}` | immediate | add `extension` to `/ws` frames |

Success: `{"success": true, "message": "success"}`.

### POST /reply

Request: `{"room": str, "type": str, "data": Any, "threadId": str?}`
`room` accepts either the room id (string of a number) or the room name.

`threadId` is the KakaoTalk thread (comment-reply) feature. It is **the log id of
the message the thread hangs off** — not a thread uuid and not your own message
id. Internally it becomes `is_chat_thread_notification=true` + `thread_id=<id>`
extras of KakaoTalk's `REPLY_MESSAGE` notification action, so the reply lands
inside that thread. When continuing an existing thread use `attachment.src_logId`
(the receiving side surfaces the same parent id there); when starting a new
thread on a message you just received use that message's `id`.

| type | data |
|---|---|
| `text` | `"message text"` |
| `image` | `"<b64>"` or `{"name": str, "b64": str, "mime"?: str}` |
| `image_multiple` | `["<b64>", ...]` or `[{"name","b64","mime"?}, ...]` — the only multi-attachment type |
| `media` / `video` / `audio` / `file` | a single object `{"name": str, "b64": str, "mime"?: str}` |

`name` is preserved and shown as the attachment filename in KakaoTalk
(mime inferred from the extension when omitted).
Response: `{"success": true, "message": "Enqueued"}` — queued, not delivered.
Delivery failures are only visible in device-side logs. An unknown `type` returns
`{"success": false, "message": "Unknown reply type: ..."}`.

### GET /rooms

`{"rooms": [{"id": str, "name": str|null, "updated_at": str}]}` — rooms with a
non-null `last_log_id`, newest first, default 30 (`?limit=`).

### GET /user/{id} · GET /user/name/{id}

`payload` = `{"id", "name", "profile_image_url"}` / `{"name"}`.
Name resolution order: `crypto_user_database.user` (plaintext) →
`db2.open_chat_member` with `enc=31` decrypted. Unknown id still returns 200 with
`name: null`.

### GET /room/{id} · /room/name/{id} · /room/{id}/members · /room/{id}/links

- `/room/{id}` → `{"id", "name", "active_member_ids", "link_id", "type", "last_updated_at"}`
  (name is `private_meta.name`)
- `/room/name/{id}` → `{"name"}` (OpenChat: `db2.open_link.name`)
- `/room/{id}/members` → `{"members": [{"user_id", "nickname", "profile_image_url", "privilege"?}]}`
  (OpenChat rooms from `open_chat_member WHERE link_id`; regular rooms parse the
  `chat_rooms.active_member_ids` JSON array)
- `/room/{id}/links` → `{"id", "name", "url", "image_url", "member_limit", "searchable", "description"}`
  or `payload: null`

### GET /reactions/{log_id}

`payload = {"reactions": [{"id": int, "emotion_id": str, "count": int, "label": str}]}`.
`log_id` is the snowflake `chat_logs.id`. Source: `db2.chat_log_meta WHERE log_id=? AND type=2`.

### GET /chat/{id}

`payload = {"msg", "room", "sender", "json"}` — the same shape as a `/ws` event,
wrapped in the payload envelope. Works with either the deletion-mark log id or
the original id.

### GET /chat/{id}/deleted

Recovers a deleted message. Same payload shape as above.

### GET /chat/{id}/prev/{n} · /chat/{id}/next/{n}

Aliases `before` / `after` behave identically. Same item shape as above.

### GET /chat/{room}/messages

Query params: `limit` (1..100, default 50), `before`, `after`, `around`
(mutually exclusive — combining two or more yields `payload:null` + `error`),
`user_id`, `types=csv`, `from_created_at` / `to_created_at` (epoch seconds).

`payload = {"items": [{"msg","room","sender","json"}], "next_cursor": str, "has_more": bool}`

Cursor is the snowflake `id`. The next older page: `...&before={next_cursor}`.
Time filters must use the `created_at` column semantics (id→time conversion is not
exact, so `id` must not be used for time arithmetic).

### DELETE /chat/logs?days=N

Permanently deletes logs older than `days` (fractional allowed). ACK response.

### POST /query

Request: `{"query": str_sql, "bind": [json_primitives]}`. Bind values must be sent
as native JSON primitives (numbers unquoted) — the server model is
`List<JsonPrimitive>` and `["4480249"]` as strings still works but numbers are the
native convention. Only SELECT statements are meaningful.
Response: `{"data": [{"col": value|null}], "error": null}`. Returned rows already
have encrypted columns (message/attachment/…) decrypted.
Schema prefixes: `db1.` (KakaoTalk.db), `db2.` (KakaoTalk2.db), `db3.`
(multi_profile_database.db) — see §5.

### POST /search

Body: `{"query": str, "limit": int}` plus optional filters `room`, `user_id`,
`types: [str]`, `from_created_at`, `to_created_at`.
Response:
```json
{"hits": [{"id": str, "preview": str_120_chars,
           "chat_id": ..., "user_id": ..., "created_at": ...,
           "type_name": ..., "room_name": ..., "sender_name": ...}],
 "error": null}
```
The enrichment fields (chat_id … sender_name) are present only when a filter was
used. `hits[].id` equals `chat_logs.id`, so feed it into `GET /chat/{id}`.
Implementation: full-text search in `crypto_database.chat_log_search`, then
batch-enriched from `chat_logs`.

### POST /decrypt

Request: `{"enc": int, "b64_ciphertext": str, "user_id": int?}`.
Response: `{"plain_text": str}`. Failures return HTTP 200 with
`plain_text = "Error: ..."`.

### GET /aot

`{"success": true, "aot": {..., "d_id": ...}}` or `{"success": false, "aot": null}`.

### GET /process-status

```json
{"server_running": bool, "port": int, "notification_polling": bool,
 "db_observing": bool, "bot_id": int, "bot_name": str, "bot_http_port": int,
 "web_server_endpoint": str, "db_polling_rate": int, "message_send_rate": int,
 "logs": ["daemon stdout/stderr, latest ~60 lines"],
 "last_logs": [{"_id": str, "chat_id": str, "user_id": str, "message": str,
                 "created_at": str, "room_name": str, "user_name": str}]}
```

### POST /process-command

`{"command": "stop" | "restart"}` → ACK. Controls the daemon from HTTP (no root needed).

### GET /openapi.json · GET /swagger

OpenAPI 3.1 document / Swagger UI. `servers.url` is built from the request Host
header, so "Try it out" works same-origin.

## 3. WebSocket `/ws`

Every received message is one JSON frame.

### ROOT_ADB · HAYUL

```json
{"msg": str, "room": str, "sender": str, "json": { <full db1.chat_logs row> }}
```

- `message`, `attachment`, `supplement` inside `json` are decrypted plaintext; the
  rest are raw DB values.
- `json.attachment` gains `src_logId` and `src_isThread` keys: for messages sent
  inside a thread, `src_logId` = the parent message's log id the thread hangs off,
  `src_isThread` = true. (Populated from `chat_logs.thread_id` /
  `supplement.threadId` when type=1.)
- Frames with `origin` SYNCMSG or MCHATLOGS are never broadcast (by default).
  System-origin events (origin not MSG) are included only when
  `includeSystemEvents` is enabled.
- When `POST /config/extension {"enable": true}` was issued, a top-level
  `extension` object is added:

```json
{"type_code": int, "type_base": int, "is_openchat": bool, "type_name": str,
 "log_id": str, "is_mine": bool, "reactions": [...], "is_deleted": true,
 "deleted_by": "writer|admin"}
```

`reactions` is present only when reactions exist. `is_deleted`/`deleted_by` only on
deletion-mark rows.

### NON_ROOT

```json
{"msg": str, "room": str, "sender": str, "is_lite": false,
 "is_group_chat": bool, "profile_image": "base64-jpeg"|null,
 "json": {"_id": null, "id": str, "type": null, "chat_id": str, "scope": null,
          "user_id": str, "message": str, "attachment": null, "created_at": null,
          "deleted_at": null, "client_message_id": null, "prev_id": null,
          "referer": null, "supplement": null, "v": null}}
```

- Only what a notification exposes exists: `room`, `sender`, `json.chat_id` (room
  id), `json.id` (chat log id), `message` is the text. Everything else is null.
- There is no reply ACK broadcast (same as original Iris; broadcasting a frame
  without `json` breaks irispy-client).

## 4. Message classification (type / origin)

`type` is numeric. OpenChat logs are `type = base + 16384` (bit). Base types:

| base | type_name | base | type_name |
|---|---|---|---|
| 1 | text | 23 | talk_memo |
| 2 | photo | 25 | app |
| 3 | video | 26 | app_feed |
| 5 | audio | 27 | list |
| 6 | file | 61 | in_link |
| 7 | contact | 71 | feed |
| 11 | photo_animation | 72 | feed_share |
| 12 | contact | 93 | current_user |
| 16 | long_app | others | unknown |
| 18 | gif | | |
| 20 | emoticon | | |

`v.origin` values:

| origin | meaning | broadcast default |
|---|---|---|
| `MSG` | real incoming message | yes |
| `WRITE` | own message saved (current_user) | yes |
| `SYNCMSG` | historical sync (echo) | never |
| `MCHATLOGS` | openchat log backfill | no |
| `NEWMEM`, `DELMEM`, `FEED`, … | system events | conditional |
| `SYNCDLMSG` | deletion mark by the writer | "deleted" filter / conditional |
| `SYNCMODMSG` | deletion mark by the room admin | same |
| `SYNCREWR` | blind cover, NOT a deletion mark | system |

`classify(type, origin)` maps by the rules above; origin SYNCDLMSG/SYNCMODMSG is
always `"deleted"` regardless of type; empty origin is `"unknown"`.

Valid `/config/types` filter tokens: `text, photo, video, audio, file, contact,
photo_animation, gif, emoticon, list, app, app_feed, feed, feed_share, talk_memo,
long_app, current_user, mchatlog, syncmsg, system, unknown, deleted`.

Event handling tips:
- `chat.sender.id == bot_id` → own message (`is_mine`).
- `type_base = type & ~16384`; OpenChat iff `is_openchat`.

## 5. Database reference

Access only via `POST /query` against db1 (KakaoTalk.db), db2 (KakaoTalk2.db),
db3 (multi_profile_database.db). The SQLCipher databases (crypto_database,
crypto_user_database) cannot be joined or queried over SQL — reach their content
through the dedicated endpoints (`/search`, `/user/...`, `/chat/...`).

### Tables and relations

```
chat_logs (db1)                      ← messages, center of every relation
 ├─ chat_id          → chat_rooms.id
 ├─ user_id          → open_chat_member.user_id / crypto_user_database.user.id
 ├─ prev_id          → chat_logs.id      (previous message in the same room)
 └─ id (snowflake)   = chat_log_meta.log_id (db2) = chat_log_search.id (crypto_database)
chat_rooms (db1)
 ├─ link_id          → db2.open_link.id   (OpenChat rooms only)
 ├─ active_member_ids JSON ids → open_chat_member.user_id
 └─ private_meta     JSON {name} (name of regular / 1:1 rooms)
db2.open_link  ←link_id—  db2.open_chat_member  —user_id→ users
db2.open_profile.link_id ↔ chat_rooms.link_id          (room profile)
db2.recommended_friends.user_id                       (friend suggestions)
db2.friends  (only ≤ KakaoTalk 26.7.2; replaced by open_chat_member)
```

### Columns used by the code (verified)

db1.chat_logs
| column | notes |
|---|---|
| `_id` | autoincrement cursor (what DBObserver polls with) |
| `id` | snowflake — the id used by every API cursor/reaction key |
| `chat_id` | room id |
| `user_id` | sender |
| `message` | encrypted (plaintext after decryption) |
| `attachment` | encrypted JSON; per type holds `url`, `imageUrls`, `C.THL`, `src_logId`, `path`, … |
| `supplement` | encrypted; threadId and companions |
| `type` | see §4 |
| `v` | JSON containing `origin` among others |
| `created_at` | epoch seconds (string) |
| `deleted_at` | > 0 marks deletion; combine with origin (§4) |
| `prev_id` | previous log id in the same room |
| `client_message_id` | dedup id |
| `scope`, `referer` | rarely populated |

db1.chat_rooms: `id`, `active_member_ids` (JSON array), `private_meta`, `link_id`,
`type`, `last_log_id`, `last_updated_at`.

db2.open_chat_member: `_id`, `link_id`, `user_id`, `nickname` (encrypted; enc=31
decrypts with bot_id as seed), `profile_image_url` (encrypted), `privilege`, `enc`.

db2.open_profile: `link_id`, `nickname`, `link_member_type`
(1 HOST / 2 NORMAL / 4 MANAGER / 8 BOT), `o_profile_image_url`.

db2.open_link: `id`, `name`, `url`, `image_url`, `member_limit`, `searchable`,
`description`.

db2.chat_log_meta: `log_id`, `type` (2 = reactions), `content` JSON `{rx:[{id, emotion_id, count, label}]}`.

db2.recommended_friends: `user_id`, `nick_name` (encrypted), `enc`.

crypto_user_database.user (plaintext): `id`, `nickname`, `profile_image_url`.
crypto_user_database.talk_channel: `id`, `name`.

crypto_database.chat_log_search: `id` (= chat_logs.id), `searchable_text` (plaintext full text).

### Keys and decryption

- `chat_logs.message/attachment/supplement`: AES keyed by `(enc, user_id)` pairs.
  Use `POST /decrypt` instead of implementing it.
- `open_chat_member` nickname/profile: `enc == 31`, seed = bot id.
- `crypto_user_database`: PBKDF2 (`se` salt + `ed` iterations) plus the user db salt
  stored in the KakaoTalk DataStore protobuf.

### SQL recipes

```sql
-- recent messages of a room
SELECT * FROM chat_logs WHERE chat_id = ? ORDER BY id DESC LIMIT 50;
-- thread source
SELECT * FROM chat_logs WHERE id = ?;          -- input: attachment.src_logId
-- one message back
SELECT * FROM chat_logs WHERE id = (SELECT prev_id FROM chat_logs WHERE id = ?);
-- OpenChat members
SELECT user_id, nickname FROM db2.open_chat_member WHERE link_id = ?;
-- room name (regular rooms)
SELECT private_meta FROM chat_rooms WHERE id = ?;
-- room name (openchat)
SELECT name FROM db2.open_link WHERE id = (SELECT link_id FROM chat_rooms WHERE id = ?);
```

## 6. irispy-client (module `iris`) API

`pip install irispy-client`. Dependencies: websockets, requests, pillow, httpx.
Import: `from iris.bot import Bot`; models via `from iris.bot.models import ...`.

### iris.bot.Bot

`__init__(iris_url: str, *, max_workers: int | None = None)`

- Attributes: `emitter: EventEmitter`, `iris_url: str` (prefix-stripped),
  `iris_ws_endpoint: str`, `api: IrisAPI`, `bot_id: int | None` (refreshed inside
  `run()` from `GET /config` → `bot_id`; None on failure).

`run()` — blocking. Connects `/ws`, dispatches events, prints connect/error lines,
retries every 3 s after disconnects, exits on Ctrl+C.

`on_event(name)` — decorator factory. Events:

| event | argument | emitted when |
|---|---|---|
| `chat` | `ChatContext` | every event, always |
| `message` | `ChatContext` | `is_lite` or `v.origin == "MSG"` |
| `new_member` | `ChatContext` | origin `"NEWMEM"` |
| `del_member` | `ChatContext` | origin `"DELMEM"` |
| `unknown` | `ChatContext` | other origins (non-lite) |
| `error` | `ErrorContext` | any callback raised |

Multiple handlers per name are allowed. `max_workers` sizes the dispatch thread pool.

### iris.bot.models.ChatContext

dataclass — the callback argument type.

| attribute | type | note |
|---|---|---|
| `room` | `Room` | |
| `sender` | `User` | |
| `message` | `Message` | |
| `raw` | dict | the raw `json` row (for lite frames it is the full lite `json` object) |
| `api` | `IrisAPI` | same server |
| `is_lite` | bool | notification-based |
| `_bot_id` | int | |

Methods:
- `reply(message: str, room_id: int = None, thread_id: int = None)` → `api.reply`.
  Swallows exceptions after printing.
- `reply_media(files: list[BufferedIOBase|bytes|Image|str], room_id=None, thread_id=None)`
  — always sends `type: image_multiple`. A lone file is wrapped in a list; `str`
  beginning with `http` is downloaded, otherwise read as a path; PIL images are
  converted to PNG. Unsupported items are skipped with a print.
- `get_source() -> ChatContext | None` — resolves `attachment.src_logId`, i.e. the
  thread's anchor message.
- `get_previous_chat(n=1) -> ChatContext | None` — walks `prev_id` n steps back
  (recursive CTE). `n < 0` raises ValueError.
- `get_next_chat(n=1) -> ChatContext | None` — n steps forward.

### iris.bot.models.Message

Attributes: `id: int|str`, `type: int|None`, `msg: str`, `attachment: dict|str|None`,
`v: dict`, `is_lite: bool`.

Derived (set in `__post_init__`):
| attr | rule |
|---|---|
| `command` | first whitespace-split token of `msg` |
| `has_param` / `param` | whether the remainder exists / the remainder string |
| `image` | `ChatImage` when base type ∈ {71, 27, 2} (incl. +16384), else None |

- `attachment` string values are JSON-parsed into dicts.
- If `msg` length ≥ 3900 and `attachment.path` exists, the full text is fetched
  from `https://dn-m.talk.kakao.com/<path>` and replaces `msg`.
- repr: `Message(id=..., type=..., msg=...)`.

### iris.bot.models.Room

Constructor `Room(id, name, api, is_lite=False, is_group_chat=False)`.
- `id`, `name`, `is_lite`, `is_group_chat()` (method).
- `type` cached_property: `chat_rooms.type` via query, None on lite or failure.

### iris.bot.models.User

Constructor `User(id, chat_id, api, name=None, bot_id=None, is_lite=False, profile_image=None)`.
- `id`, `is_lite`, `avatar: Avatar`.
- `name` cached_property: prefills from the event; if absent and id == bot_id, reads
  `open_profile` via `chat_rooms.link_id`; otherwise tries `db2.friends` then
  `db2.open_chat_member` for a nickname; None on failure.
- `type` cached_property: `link_member_type` mapped to `"HOST"|"NORMAL"|"MANAGER"|"BOT"`,
  `"UNKNOWN"` on odd values, `"REAL_PROFILE"` when the query throws.

### iris.bot.models.Avatar

- `url` cached_property: for ids < 1e10 reads `open_profile.o_profile_image_url`
  (room profile), else `open_chat_member.original_profile_image_url`; None on lite/failure.
- `img` cached_property: lite → decodes `profile_image` b64 into PIL; otherwise
  downloads `url` → RGBA PIL. None on failure.

### iris.bot.models.ChatImage

Built from a Message (`message.image`).
- `url`: list of URLs — `attachment["C"]["THL"][*]["TH"]["THU"]` for type 71,
  `attachment["imageUrls"]` for 27, else `[attachment["url"]]`; None on failure.
- `img`: `list[PIL.Image]` RGBA, or None.

### iris.bot.models.ErrorContext

Fields: `event: str`, `func: Callable`, `exception: Exception`, `args: list` — the
argument of `on_event("error")`.

### iris.bot._internal.iris.IrisAPI

Holds `iris_endpoint` (with `http://`). All non-2xx raise
`Exception("Iris 오류: <message>")`.

| method | endpoint | returns |
|---|---|---|
| `reply(room_id: int, msg: str, thread_id=None)` | POST /reply `type=text` | dict ACK |
| `reply_media(room_id, files, thread_id=None)` | POST /reply `image_multiple` | dict ACK, or prints-and-returns None if all conversions failed |
| `query(query: str, bind: list|None=None)` | POST /query | `list[dict]` |
| `decrypt(enc: int, b64: str, user_id: int)` | POST /decrypt | `str|None` (plain_text) |
| `get_info()` | GET /config | dict |
| `get_aot()` | GET /aot | dict |

### iris.bot._internal.emitter.EventEmitter

`register(name, func)`, `emit(name, args)` — lowercased names, dispatched to a
ThreadPoolExecutor. A raising handler triggers `emit("error", [ErrorContext])`.
Each handler run closes a `PyKV` session and flushes stdout.

### iris.decorators

- `@has_param` — runs only when `message.param` exists.
- `@is_reply` — passes only `type == 26` or `attachment.src_isThread`; otherwise
  auto-replies "메시지에 답장하여 요청하세요." and skips.
- `@is_admin` / `admin_check(chat)` — PyKV key `admin` list membership.
- `@is_not_banned` / `ban_check(chat)` — PyKV key `ban` list membership.
  Caution: the deny path returns an empty string silently.
- Populate admin/ban lists with the `iris admin add` / `iris ban add` CLI.

### iris.util.PyKV

Process-wide singleton SQLite store (`iris.db`, table `kv_pairs(key TEXT PRIMARY
KEY, value TEXT)` — values JSON-encoded).

`open(filename)`, `close()`, `get(key)` → value or `False`, `get_kv(key)` →
`{"key","value"}` or `False`, `put(key, value)` (INSERT OR REPLACE),
`search(needle)` (LIKE on value JSON), `search_json(valueKey, needle)` (dot-path),
`search_key(needle)` (LIKE on key), `list_keys()`, `delete(key)`.

### iris.kakaolink.IrisLink

`IrisLink(iris_url)` — needs PyKV key `kakaolink_config` = `{"app_key", "origin"}`
(set via `iris kakaolink <app_key> <origin>`).
`send(receiver_name, template_id, template_args, app_key=None, origin=None,
search_exact=True, search_from="ALL|FRIENDS|CHATROOMS",
search_room_type="ALL|OpenMultiChat|MultiChat|DirectChat")` — searches a friend or
room by name and sends a KakaoLink template.
Exceptions: `KakaoLinkException` (base), `KakaoLinkReceiverNotFoundExcepetion`,
`KakaoLinkLoginExcepetion`, `KakaoLink2FAExcepetion`, `KakaoLinkSendExcepetion`
(original misspellings preserved).

### iris.cli (`iris` console script)

- `iris init [--force]` — scaffold `irispy.py`, `iris.db`, `.env`
- `iris kakaolink <app_key> <origin>`
- `iris admin {add|del|list} [user_id]`
- `iris ban {add|del|list} [user_id]`
- `iris service {create|start|stop|restart|status}` — systemd integration

## 7. Recipes

### Command bot

```python
from iris.bot import Bot

bot = Bot("192.168.0.50:3000")

@bot.on_event("message")
def on_message(ctx):
    if ctx.message.command == "!ping":
        ctx.reply("pong")

if __name__ == "__main__":
    bot.run()
```

Do not reply unless the condition matches — replying to every message is spam.

### Pin to one room

```python
@bot.on_event("message")
def on_message(ctx):
    if int(ctx.room.id) != 479588580459315:
        return
    ...
```

### Attach a generated image

```python
from PIL import Image

@bot.on_event("message")
def on_message(ctx):
    if ctx.message.command == "!img":
        img = Image.new("RGBA", (300, 300), (255, 0, 0, 255))
        ctx.reply_media([img], thread_id=ctx.message.id)  # start a thread under this message
```

### Thread handling

A KakaoTalk thread is anchored to one message. Received replies inside a thread
carry `attachment.src_logId` = that anchor message's log id and `src_isThread=true`.
Replying with `thread_id` set re-enters the same thread (the value is the anchor
id, see §2).

```python
att = ctx.message.attachment or {}

if att.get("src_isThread"):
    # received inside an existing thread — answer back into the same thread
    ctx.reply("still on-topic", thread_id=int(att["src_logId"]))
elif ctx.message.command == "!thread":
    # open a new thread anchored to the message we just received
    ctx.reply("new thread opened", thread_id=ctx.message.id)

# resolve the anchor message itself:
anchor = ctx.get_source()   # ChatContext | None, via attachment.src_logId
```

### Read history

```python
rows = ctx.api.query(
    "SELECT * FROM chat_logs WHERE chat_id = ? ORDER BY id DESC LIMIT 50",
    [ctx.room.id])
```

### Video / file / audio (not covered by reply_media)

```python
import base64, requests
requests.post(f"{ctx.api.iris_endpoint}/reply", json={
    "room": str(ctx.room.id), "type": "video",
    "data": {"name": "clip.mp4", "b64": base64.b64encode(open("clip.mp4","rb").read()).decode()},
})
```

### Health check

```python
cfg = ctx.api.get_info()
if cfg["bot_id"] == 0:
    ...  # polling stopped — warn the operator
```

## 8. Pitfalls

- `Bot("example.com:3000")` raises ValueError — dotted-quad IP + port only.
- `reply_media` can only produce `image_multiple`. For video/audio/file, POST
  `/reply` directly with the object payload shown in §2/§7.
- `message.attachment` may arrive as a string; it is auto-parsed in `Message`, but
  treat None-safe before `.get`.
- lite mode (`is_lite`): `attachment=None`, `type=None`, `v=null`, no `image`
  object. Guard every non-lite path with `not ctx.is_lite`.
- `get_source/get_previous_chat/get_next_chat` are non-lite only — expect None on lite.
- `is_not_banned` denies silently (returns `""`). Use it only when the quiet deny is intended.
- Rate limiting: `message_send_rate` throttles the server send queue (default
  50 ms). Nothing prevents code-level reply storms; register exactly one `reply`
  per event.
- `api.query` binds: prefer unquoted JSON numbers (`[4480249]`, not `["4480249"]`).
- `before`/`after` cursors exclude the anchor, `around` includes it; time filters
  work only on `created_at`.
- Every `/chat/...`, `/reactions/...` id is `chat_logs.id` (snowflake), never `_id`.
- `thread_id`/`threadId` is the anchor message's log id, not a thread uuid. To
  continue an existing thread pass `int(attachment.src_logId)`; passing
  `ctx.message.id` opens a *new* thread under that message instead.
- `run()` never exits on its own; only KeyboardInterrupt stops it.
