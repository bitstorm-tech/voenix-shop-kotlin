# Decimal columns in Exposed

Stand: 14. Juli 2026

## Fragestellung

Kann Exposed eine PostgreSQL-Spalte vom Typ `numeric` ohne festgelegte
Precision und Scale mit einem eingebauten Spaltentyp abbilden, oder muss
`decimal()` weiterhin beide Werte erhalten?

## Ergebnis

Exposed 1.3.1 verlangt bei der eingebauten `decimal()`-Spaltendefinition
weiterhin sowohl `precision` als auch `scale`. Das ist kein veraltetes Verhalten
einer früheren Projektversion: Das Backend verwendet bereits Exposed 1.3.1, und
die aktuelle offizielle Dokumentation beschreibt genau diese API.

Es gibt in der öffentlichen Tabellen-API von Exposed 1.3.1 keine zusätzliche
`numeric()`-Methode und keinen `decimal()`-Overload ohne diese beiden Angaben.
Wer PostgreSQL-`numeric` ohne Begrenzung abbilden möchte, benötigt daher
weiterhin einen eigenen `ColumnType`.

## Belege

- Der zentrale Dependency Catalog legt `exposed-core` und `exposed-jdbc` auf
  Version 1.3.1 fest:
  [`libs.versions.toml`](../../../backend/libs.versions.toml).
- Die [offizielle Exposed-1.3.1-Dokumentation zu numerischen
  Typen](https://www.jetbrains.com/help/exposed/numeric-boolean-string-types.html)
  beschreibt `decimal()` als Abbildung auf `DECIMAL` mit angegebener Precision
  und Scale.
- Im [offiziellen Quellcode von `Table` aus Exposed
  1.3.1](https://github.com/JetBrains/Exposed/blob/1.3.1/exposed-core/src/main/kotlin/org/jetbrains/exposed/v1/core/Table.kt#L876-L889)
  lautet die einzige eingebaute Spaltendefinition
  `decimal(name: String, precision: Int, scale: Int)`.
- Der [veröffentlichte API-Dump von Exposed
  1.3.1](https://github.com/JetBrains/Exposed/blob/1.3.1/exposed-core/api/exposed-core.api#L2399-L2403)
  bestätigt diese Signatur für die öffentliche `Table`-API.
- `DecimalColumnType` erzeugt laut [offizieller
  Implementierung](https://github.com/JetBrains/Exposed/blob/1.3.1/exposed-core/src/main/kotlin/org/jetbrains/exposed/v1/core/ColumnType.kt#L683-L716)
  immer `DECIMAL(precision, scale)` und setzt gelesene Werte mit
  `RoundingMode.HALF_EVEN` auf die konfigurierte Scale.
- PostgreSQL selbst benötigt diese Einschränkung nicht. Laut [offizieller
  PostgreSQL-Dokumentation](https://www.postgresql.org/docs/current/datatype-numeric.html)
  erzeugt `NUMERIC` ohne Precision und Scale eine unbegrenzte Numeric-Spalte,
  die Eingaben nicht auf eine bestimmte Scale zwingt.

## Konsequenz für Pricing

Pricing begrenzt Prozentwerte nun bewusst auf vier Vor- und zwei
Nachkommastellen. `PricePercentageColumnType` wurde deshalb durch Exposeds
eingebautes `decimal(6, 2)` ersetzt. Die API-Validierung lehnt Werte mit mehr als
zwei relevanten Nachkommastellen oder außerhalb des darstellbaren Bereichs ab;
die nachgelagerte UI-Aufgabe spiegelt diese Regeln.

Für ein zukünftiges Feld, das tatsächlich PostgreSQL-`numeric` ohne Precision
und Scale benötigt, wäre ein eigener Column Type weiterhin gerechtfertigt.
