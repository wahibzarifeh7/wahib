"""Dump the Excel sheet to data/excel_rows.json so build_db.mjs can parse it.
Usage: python tools/dump_excel.py "C:\path\to\mobile_phone_models.xlsx"
"""
import json, sys, openpyxl

wb = openpyxl.load_workbook(sys.argv[1], data_only=True)
ws = wb.active
rows = []
for box, text in ws.iter_rows(min_row=2, max_col=2, values_only=True):
    if box is not None and text:
        rows.append({"box": int(box), "text": str(text)})
with open("data/excel_rows.json", "w", encoding="utf-8") as f:
    json.dump(rows, f, ensure_ascii=False, indent=1)
print(f"{len(rows)} boxes written")
