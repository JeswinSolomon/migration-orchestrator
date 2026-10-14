"""Generates deliberately dirty legacy loan/billing CSVs for profiling and migration practice."""
import csv
import random
from datetime import date, timedelta
from pathlib import Path

random.seed(42)
OUT = Path(__file__).parent / "data"
OUT.mkdir(exist_ok=True)

N_BORROWERS, N_LOANS, N_PAYMENTS = 5000, 6000, 60000
DATE_FORMATS = ["%Y-%m-%d", "%m/%d/%Y", "%d-%b-%y"]  # mixed formats on purpose
FIRST = ["Ana", "Ben", "Chen", "Dina", "Eli", "Farah", "Gus", "Hana", "Ivan", "Jo"]
LAST = ["Smith", "Patel", "Garcia", "Kim", "Nguyen", "Brown", "Khan", "Lopez"]


def messy_date(d):
    return d.strftime(random.choice(DATE_FORMATS)) if random.random() > 0.02 else ""


with open(OUT / "borrowers.csv", "w", newline="", encoding="latin-1") as f:
    w = csv.writer(f)
    w.writerow(["borrower_id", "full_name", "email", "phone", "dob"])
    for i in range(1, N_BORROWERS + 1):
        name = f"{random.choice(FIRST)} {random.choice(LAST)}"
        w.writerow([i, name if random.random() > 0.01 else name.upper(),
                    f"{name.replace(' ', '.').lower()}{i}@example.com" if random.random() > 0.05 else "",
                    random.choice(["555-0100", "(555) 0101", "5550102", ""]),
                    messy_date(date(1950, 1, 1) + timedelta(days=random.randint(0, 20000)))])
    for i in random.sample(range(1, N_BORROWERS), 80):  # duplicate borrowers
        w.writerow([N_BORROWERS + i, f"{random.choice(FIRST)} {random.choice(LAST)}", "", "", ""])

with open(OUT / "loans.csv", "w", newline="", encoding="latin-1") as f:
    w = csv.writer(f)
    w.writerow(["loan_id", "borrower_id", "principal", "rate_pct", "start_date", "status", "balance"])
    for i in range(1, N_LOANS + 1):
        p = round(random.uniform(1000, 50000), 2)
        w.writerow([i, random.randint(1, N_BORROWERS) if random.random() > 0.01 else 999999,  # orphans
                    p, random.choice([3.5, 4.25, "5.1%", 6]),
                    messy_date(date(2015, 1, 1) + timedelta(days=random.randint(0, 3600))),
                    random.choice(["A", "ACTIVE", "C", "CLOSED", "D", "DEFAULT"]),
                    round(p * random.random(), 2) if random.random() > 0.03 else ""])

with open(OUT / "payments.csv", "w", newline="", encoding="latin-1") as f:
    w = csv.writer(f)
    w.writerow(["payment_id", "loan_id", "amount", "paid_on"])
    for i in range(1, N_PAYMENTS + 1):
        w.writerow([i, random.randint(1, N_LOANS), round(random.uniform(-50, 1500), 2),
                    messy_date(date(2015, 1, 1) + timedelta(days=random.randint(0, 3900)))])

print("generated in", OUT)
