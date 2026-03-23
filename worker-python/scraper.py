import json
import requests

API_URL = "https://iis.bsuir.by/api/v1/employees/all"
OUTPUT_FILE = "teachers.json"


def fetch_teachers() -> list:
    print(f"[SCRAPER] Запрашиваю данные с {API_URL} ...")
    response = requests.get(API_URL, timeout=30)
    response.raise_for_status()

    data = response.json()
    print(f"[SCRAPER] Получено {len(data)} записей из API")

    teachers = []
    for emp in data:
        fio = emp.get("fio", "").strip()
        if not fio:
            continue

        email = None
        contact_info = emp.get("contactInfo")
        if contact_info:
            for item in contact_info:
                if item.get("contactType") == "email":
                    email = item.get("contactValue")
                    break

        if email is None:
            email = emp.get("email") or emp.get("calendarId")

        teachers.append({
            "fio": fio,
            "email": email,
        })

    return teachers


def main():
    teachers = fetch_teachers()
    print(f"[SCRAPER] Собрано преподавателей с ФИО: {len(teachers)}")

    with_email = [t for t in teachers if t["email"]]
    print(f"[SCRAPER] Из них с почтой: {len(with_email)}")

    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(teachers, f, ensure_ascii=False, indent=2)

    print(f"[SCRAPER] Результат сохранён в {OUTPUT_FILE}")


if __name__ == "__main__":
    main()
