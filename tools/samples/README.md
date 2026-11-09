# Sample bank tools

Builds `app/src/main/assets/samples` from VSCO 2 Community Edition (CC0).

    gh api "repos/sgossner/VSCO-2-CE/git/trees/master?recursive=1" --jq '.tree[] | select(.type=="blob") | "\(.size)\t\(.path)"' > tree.tsv
    python pick.py tree.tsv pick.json      # choose notes per instrument
    python dl.py                           # download into raw/
    python build.py ../../app/src/main/assets/samples   # trim, 24 kHz mono, loops, bank.txt

Run from a scratch folder that holds these scripts (needs numpy).
