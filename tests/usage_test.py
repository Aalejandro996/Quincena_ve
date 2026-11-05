"""Uso real de la app: quién la usa, quién no, quién tiene la app vieja, y el recordatorio para volver. Sin red."""
import os
import sys
import tempfile
from datetime import datetime, timedelta, timezone

tmp = tempfile.mkdtemp()
os.environ.update(DATABASE_URL=f"sqlite:///{tmp}/t.db", APP_SECRET_KEY="x" * 48, ALLOW_REGISTRATION="true", BOOTSTRAP_TOKEN="b" * 32,
                  COOKIE_SECURE="false", API_DEFAULT_LIMIT="5000 per minute", RATELIMIT_STORAGE_URI="memory://", BREVO_API_KEY="k", SMTP_USER="",
                  SMTP_PASSWORD="", APP_PUBLIC_ORIGIN="https://quincena.example", MAIL_FROM="Quincena VE <hola@quincena.example>")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import app as A  # noqa: E402
import usage  # noqa: E402

ok = fail = 0


def check(name, cond, extra=""):
    global ok, fail
    if cond:
        ok += 1; print("  ok  ", name)
    else:
        fail += 1; print("  FALLA", name, extra)


A.send_verification = lambda user: True
A.notify_security = lambda *a, **k: None
A.fetch_rate = lambda country="VE", force=False: {"rate": 200.0}
SENT = []
usage.send_mail = lambda to, subject, body, html=None, attachments=None: SENT.append((to, subject, body)) or True


class C:
    def __init__(self):
        self.c, self.csrf = A.app.test_client(), None

    def req(self, method, path, body=None, ip="198.51.100.9", headers=None):
        h = {"X-Forwarded-For": ip, **({"X-CSRF-Token": self.csrf} if self.csrf and method != "GET" else {}), **(headers or {})}
        r = self.c.open(path, method=method, json=body, headers=h)
        j = r.get_json(silent=True) or {}
        if j.get("csrf_token"):
            self.csrf = j["csrf_token"]
        return r.status_code, j


admin = C()
admin.req("POST", "/api/setup", {"bootstrap_token": "b" * 32, "username": "admin", "password": "Zeta-Roble-9182x"})
people = {}
for i, name in enumerate(("activa", "nunca", "dejo", "nueva_hoy")):
    people[name] = C()
    people[name].req("POST", "/api/register", {"username": name, "email": f"{name}@x.com", "password": "Zorro-Plata-55231x", "accept_terms": True}, ip=f"198.51.100.{30 + i}")

# «activa» y «dejo» anotan algo; «nunca» y «nueva_hoy» no anotan nada.
for name in ("activa", "dejo"):
    code, st = people[name].req("GET", "/api/state")
    import copy
    base = copy.deepcopy(st["data"]); data = st["data"]
    data["expenses"] = [{"id": "e1", "amount": 5, "currency": "USD", "category": "comida", "description": "Mercado", "method": "efectivo", "date": "2026-11-01T12:00:00Z"}]
    code, _ = people[name].req("PUT", "/api/state", {"data": data, "base_data": base, "base_revision": st["revision"]})
    assert code == 200, code

now = datetime.now(timezone.utc)
with A.app.app_context():
    U = {u.username: u for u in A.User.query.all()}
    U["nunca"].created_at = now - timedelta(days=3)
    U["dejo"].created_at = now - timedelta(days=20); U["dejo"].last_seen_at = now - timedelta(days=9)
    A.SharedState.query.filter_by(user_id=U["dejo"].id).first().updated_at = now - timedelta(days=9)
    A.db.session.commit()

print("== Se anota el uso ==")
people["activa"].req("GET", "/api/state", headers={"User-Agent": "okhttp/4.12", "X-App-Version": "999999"})
with A.app.app_context():
    u = A.User.query.filter_by(username="activa").first()
    check("guarda cuándo entró, desde la app y su versión", u.last_seen_at is not None and u.last_platform == "android" and u.app_version == 999999, (u.last_platform, u.app_version))

print("== Panel: uso real ==")
check("solo para administradores", people["activa"].req("GET", "/api/admin/usage")[0] == 403)
code, r = admin.req("GET", "/api/admin/usage")
m = r["summary"]; by = {x["username"]: x for x in r["users"]}
check("cuenta 4 personas (sin el admin)", code == 200 and m["total"] == 4, m)
check("«activa» la usa de verdad", by["activa"]["status"] == "activa", by["activa"])
check("«dejo» la dejó (9 días sin entrar)", by["dejo"]["status"] == "inactiva" and by["dejo"]["days_idle"] >= 9, by["dejo"])
check("«nunca» y «nueva_hoy» nunca la usaron", by["nunca"]["status"] == "nunca_uso" and by["nueva_hoy"]["status"] == "nunca_uso")
check("resumen: 1 activa, 1 la dejó, 2 nunca la usaron", (m["activa"], m["inactiva"], m["nunca_uso"]) == (1, 1, 2), m)
check("le toca recordatorio a «dejo» y a «nunca», no a quien se registró hoy ni a la activa",
      by["dejo"]["due"] and by["nunca"]["due"] and not by["nueva_hoy"]["due"] and not by["activa"]["due"] and m["due"] == 2, m)

print("== Recordatorio ==")
code, prev = admin.req("POST", "/api/admin/usage/remind", {})
check("primero muestra la lista sin enviar nada", code == 200 and prev["dry_run"] and prev["count"] == 2 and not SENT, prev)
code, res = admin.req("POST", "/api/admin/usage/remind", {"confirm": True})
check("al confirmar se envían 2 correos", code == 200 and res["sent"] == 2 and len(SENT) == 2, res)
txt = {to: body for to, _, body in SENT}
check("a quien nunca la usó le explica cómo empezar", "todavía no has anotado nada" in txt["nunca@x.com"])
check("a quien la dejó le dice cuántos días lleva", "días que no pasas por Quincena VE" in txt["dejo@x.com"] and "https://quincena.example/app" in txt["dejo@x.com"])
code, again = admin.req("POST", "/api/admin/usage/remind", {"confirm": True})
check("no se repite antes de 7 días", again["count"] == 0 and len(SENT) == 2, again)

print("== App vieja ==")
apk = A.APK_PATH
made = not apk.is_file()
if made:
    apk.parent.mkdir(parents=True, exist_ok=True); apk.write_bytes(b"apk")
try:
    latest = A.android_version()["versionCode"]
    code, j = people["activa"].req("GET", "/api/state", headers={"User-Agent": "okhttp/4.12", "X-App-Version": str(latest - 1)})
    check("una versión vieja de la app no funciona (pide actualizar)", code == 426 and j.get("code") == "update_required", (code, j))
    with A.app.app_context():
        u = A.User.query.filter_by(username="activa").first()
        check("al bloquearla se anota la versión vieja", u.app_version == latest - 1 and u.last_platform == "android", (u.app_version, u.last_platform))
    code, r = admin.req("GET", "/api/admin/usage")
    check("el panel la cuenta como app vieja", r["summary"]["old_app"] == 1 and next(x for x in r["users"] if x["username"] == "activa")["old_app"], r["summary"])
finally:
    if made:
        apk.unlink()

print(f"\n{ok} ok, {fail} fallas")
sys.exit(1 if fail else 0)
