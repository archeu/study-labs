import warnings; warnings.filterwarnings("ignore")
from fastapi.testclient import TestClient
from models import app
c = TestClient(app, raise_server_exceptions=False)
for p in ("/gc/summary?service=order&minutes=10", "/gc/summary?service=order&minutes=0",
          "/gc/summary?service=order&minutes=abc", "/gc/summary?minutes=10",
          "/gc/leak?service=order", "/gc/bad?service=order"):
    r = c.get(p); print(p, r.status_code, r.text[:230])
