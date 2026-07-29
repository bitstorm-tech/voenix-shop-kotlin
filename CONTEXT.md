# Voenix Shop

An e-commerce shop for personalized print products (currently mugs), being
migrated module by module from a legacy .NET backend to Kotlin. One bounded
context; the Kotlin modules share this language.

## Language

**Category**:
The top level of the shared, article-type-agnostic structure that groups
articles for the storefront navigation and the admin.
_Avoid_: taxonomy, Taxonomie

**Subcategory**:
The second and lowest level of that structure; always belongs to exactly one
category.
_Avoid_: taxonomy, Taxonomie

**Category structure**:
Categories and subcategories together, when both levels are meant. Package,
table, and prose say "category" — never "taxonomy"; the term was used during
the Article migration and is retired (decision by Joe, 2026-07-28).
_Avoid_: taxonomy, Taxonomie, classification

**Article type**:
The kind of product an article is (today: mug). Each type owns its own table
and admin routes; the category structure is shared across all types.

**Prompt slot**:
A named position in a prompt (e.g. a style or background axis) that groups
interchangeable slot variants. Replaces the legacy term "slot type"
(decision by Joe, 2026-07-28).
_Avoid_: slot type

**Prompt slot variant**:
One concrete option of a prompt slot; its text is appended to the prompt's
own text when the final generation prompt is composed. Variant names are
globally unique across all slots.

**Composed prompt text**:
The generation text a prompt produces: its own text followed by the text of
every slot variant it uses, ordered by slot and joined by a blank line. It is
composed while reading, never stored, and it is what the `PromptCatalog`
capability hands to the image generator.
_Avoid_: final prompt, full prompt

**Article identity**:
The type-independent registration of an article (and its variants) that gives
carts and orders one foreign-key target across per-type tables. Carries no
business data.
