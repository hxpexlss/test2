#!/bin/bash
# Doppelklick im Finder startet die Brücke. Beim ersten Start wird alles installiert.
cd "$(dirname "$0")" || exit 1
if [ ! -d .venv ]; then
  echo "Erster Start: installiere Abhängigkeiten …"
  python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt || {
    echo "Installation fehlgeschlagen. Ist Python 3 installiert? (https://www.python.org/downloads/)"
    read -r -p "Enter zum Schließen"
    exit 1
  }
fi
.venv/bin/python click_bridge.py "$@"
