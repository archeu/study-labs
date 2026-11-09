import json,sys
a=json.loads(open(sys.argv[1]).read()); b=json.loads(open(sys.argv[2]).read())
def mean(k):
    c=b[k].get("COUNT",0)-a[k].get("COUNT",0); t=b[k].get("TOTAL_TIME",0)-a[k].get("TOTAL_TIME",0)
    return (t/c*1000) if c else 0, c
for k in ("sql","acq","http","httpo"):
    m,c=mean(k); 
    if c: print(f"{k}: mean={m:.1f}ms count={c:.0f} max(recent)={b[k].get('MAX',0)*1000:.0f}ms")
