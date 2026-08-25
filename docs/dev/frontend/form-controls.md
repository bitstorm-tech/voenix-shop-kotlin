# Form controls in the frontend

Every text field in the frontend is a component from
[`frontend/src/components/ui/`](../../../frontend/src/components/ui/), never a
raw `<input>`. This guide explains the two text-input primitives — `Input` and
`PasswordInput` — and the boundary rule that forces you to use them.

## The UI boundary

Views, layouts, and feature components must not render raw interactive tags.
`<button>`, `<input>`, `<select>`, `<textarea>`, the app's `<table>` structure,
and interactive styled `<label>` controls may only appear inside
`src/components/ui/**`. Everywhere else you compose the primitives from that
folder.

The rule is machine-checked by
[`frontend/scripts/check-ui-boundary.mjs`](../../../frontend/scripts/check-ui-boundary.mjs),
which is part of `bun run lint` and can be run alone:

```bash
cd frontend
bun run check:ui-boundary
```

The script parses every `.vue` template under `src/components`, `src/views`, and
`src/layouts`, skips everything inside `src/components/ui`, and reports each raw
tag with its file and line. The same script also forbids importing `reka-ui`
outside `src/components/ui` — Reka behavior gets wrapped in a Voenix primitive
first.

So when you need a new kind of control, the answer is never "just this one raw
`<input>` in my view"; it is a new primitive under `src/components/ui/`.

## `Input` versus `PasswordInput`

Use [`Input`](../../../frontend/src/components/ui/input/Input.vue) for every
normal text field: text, email, number, search. It is a thin wrapper around
`<input>` with the shop's styling and a `v-model`.

Use [`PasswordInput`](../../../frontend/src/components/ui/password-input/PasswordInput.vue)
for every secret the user types: account passwords, but also API secrets such as
the SFTP password and the SPOD access token in the admin dialog. It is an
`Input` plus a `Button` with an eye icon that switches the field between masked
and clear text. There is no `type="password"` anywhere in `src/` any more — if
you find yourself typing it, you want `PasswordInput`.

## The `PasswordInput` API

```vue
<PasswordInput
  id="password"
  v-model="password"
  required
  autocomplete="current-password"
  :label="t('common.showPassword')"
/>
```

It declares exactly three props:

| Prop | Type | Meaning |
| --- | --- | --- |
| `modelValue` | `string \| number` | The typed value; use it through `v-model`. |
| `class` | Tailwind classes | Merged onto the inner `<input>`, on top of the space the icon needs. |
| `label` | `string` | The accessible name of the toggle button. Default: `Show password`. |

It emits only `update:modelValue`.

**Never pass `type`.** The component owns the type: it renders
`type="password"` while the value is masked and `type="text"` while it is
revealed. The binding sits *after* `v-bind="$attrs"` in the template, so a
`type` passed by a caller is overwritten and silently ignored. Passing `type`
would be a bug either way, because a `PasswordInput` that shows an email field
makes no sense.

## How attribute forwarding works

The component sets `defineOptions({ inheritAttrs: false })` and puts
`v-bind="$attrs"` on the inner `Input`. Without that, Vue would drop every
undeclared attribute on the wrapping `<div class="relative">`, where it would do
nothing.

Because of the forwarding, `id`, `name`, `autocomplete`, `required`,
`minlength`, `placeholder`, `aria-invalid`, and `data-testid` all land on the
real `<input>` element — exactly where a `<Label for="…">`, the browser's form
validation, a password manager, and the existing view tests look for them. Keep
them there: several specs (for example
`frontend/src/views/auth/__tests__/LoginView.spec.ts`) address the field through
its `id` or `data-testid`, and moving those onto the wrapper would break both
the tests and the accessibility of the label.

The toggle is deliberately a `<Button type="button" …>`. `Button` renders a
reka-ui `Primitive` and does **not** default the `type` attribute, so without
the explicit `type="button"` the first click on the eye icon would submit the
surrounding login form. A spec case guards this.

The button also uses `@pointerdown.prevent`. A mouse or touch press would
otherwise move focus out of the input, and on a phone that closes the virtual
keyboard mid-typing. Keyboard activation (Tab, then Space or Enter) is
unaffected.

The accessible name stays constant and the state travels in `aria-pressed`
(`false` while masked, `true` while revealed). This is the WAI-ARIA toggle
button pattern. Do not swap the label between "Show" and "Hide" as well — a
screen reader would then announce the state twice.

Nothing is persisted: every mount starts masked, and a password/confirm pair
toggles independently because each instance owns its own state.

## Who owns the label text

`src/components/ui/**` never imports `vue-i18n`. A primitive must work on the
admin surface, which is deliberately English-only, so translation happens at the
call site:

- Shop and auth views pass `:label="t('common.showPassword')"`. The key lives in
  [`frontend/src/i18n/locales/de.json`](../../../frontend/src/i18n/locales/de.json)
  and [`en.json`](../../../frontend/src/i18n/locales/en.json) under `common`;
  `src/i18n/__tests__/locales.spec.ts` enforces that both files carry the same
  keys.
- Admin components pass a plain English string when the default does not fit —
  the SPOD field uses `label="Show access token"` — or pass nothing at all and
  take the `Show password` default, as the SFTP password field does.

## Accepted gaps

These are known and deliberately not fixed:

- The toggle is 36×36 px, slightly under the 44 px touch target some guidelines
  ask for. It matches the height of the field, which is worth more here.
- Some browsers move the caret to the end of the value when the input type
  changes.
- A password manager may draw its own icon into the same corner and overlap the
  eye.
- A `disabled` attribute reaches the inner input through `$attrs`, but not the
  toggle button, which stays clickable. No caller needs a disabled password
  field today; add the binding to the `Button` when the first one appears.
