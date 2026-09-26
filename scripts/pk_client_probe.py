import base64,hashlib,json,time,random,urllib.request
BASE="https://api.ets100.com/ets/api/"
REF="https://api.ets100.com"
SECRET="317e3cdc7314959fa2ef469d38f05673"
PID="androidV3"
ETS_APP="0"
VER="7.0.67.2495(2495)"
TOKEN="eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJjcmVhdGUiOjE3OTAzOTYzNzYsImlkIjoiMzI0NDUyNjgiLCJsb2dpbiI6ImVxZmM5MDA4NSJ9.izXWQWZpnaHkOmuo8z1eXBCe3e1xiCuyRF2CU21LxVI"

def call(path, params=None):
    params = dict(params or {})
    t = int(time.time()*1000)
    sn = str(t) + str(random.randint(1000, 9999))
    params.update({"system":"2","global_client_version":VER,"sn":sn,"token":TOKEN,"ets_app":ETS_APP,"sign_response":2})
    inner = [{"r": path, "params": params}]
    s = json.dumps(inner, separators=(',',':'), ensure_ascii=False).replace("\\/","/")
    body = base64.b64encode(s.encode()).decode()
    ts = str(t)
    sign = hashlib.md5((PID+ts+body+SECRET).encode()).hexdigest()
    outer = {"body":body,"head":{"sign":sign,"pid":PID,"time":ts,"time_second":t,"version":"1.0"}}
    data = json.dumps(outer, separators=(',',':')).encode()
    req = urllib.request.Request(BASE+path, data=data, headers={"Content-Type":"application/json","Referer":REF})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read().decode("utf-8","replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8","replace")

for p in ["g/pk/count","g/pk/summary-word"]:
    st, body = call(p, {})
    print(p, st, body[:300])
