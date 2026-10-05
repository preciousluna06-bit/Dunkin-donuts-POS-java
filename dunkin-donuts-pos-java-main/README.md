# Dunkin' Donuts POS (Java GUI Prototype)

A point-of-sale (POS) **GUI prototype** for a Dunkin' Donuts-style shop, built with **Java Swing**.
It is a single-file desktop app that shows a categorized product menu with pictures, builds an order,
and prints a receipt with VAT.

> **Prototype / educational project.** Not affiliated with, endorsed by, or connected to Dunkin' Brands
> or Inspire Brands. "Dunkin'" and related names, logos and product images are trademarks of their
> respective owners and are used here for demonstration only.

## Features

- Category-based menu (classic, premium and supreme donuts, munchkins, coffee, cold drinks, milk-based drinks, add-ons, donut box combos)
- Product cards with images
- Order table with quantity and line totals
- VAT computation and receipt output
- Configurable store name, address, TIN, cashiers, terminal, currency and VAT rate
- Menu driven by a CSV file. Pictures dropped into a category folder are picked up automatically

## Tech stack

- Java (JDK 8 or newer)
- Swing / AWT (no external dependencies)

## Getting started

### Requirements

- JDK 8+ with `javac` and `java` on your `PATH`

### Run on Windows

```bat
run.bat
```

### Run manually

```bash
mkdir out
javac -encoding UTF-8 -d out src/DunkinPOS.java
java -Dfile.encoding=UTF-8 -cp out DunkinPOS
```

Run it from the project root so the `assets/` folder is found.

## Project structure

```
DunkinPOS/
├── src/DunkinPOS.java     # Application source
├── assets/
│   ├── menu.csv           # Category,Name,Price,ImageFile
│   ├── config.properties  # Store info, VAT, cashiers, currency
│   ├── README.txt         # How to add menu items
│   └── ass-ets/           # Product images by category
└── run.bat                # Compile and launch (Windows)
```

## Customizing the menu

1. Put a PNG/JPG in the matching category folder.
2. Add a line to `assets/menu.csv`: `Category,Name,Price,ImageFile`.
   Images not listed in the CSV are still shown, priced at `default.price`.
3. Edit store details and VAT in `assets/config.properties`.
4. Restart the app.

## Status

Early prototype. There is no database, payment integration or persistent sales history yet.

## License

Released under the [MIT License](LICENSE).
