import sys
import pandas as pd
from pathlib import Path

RAW = Path("data/raw")
mode, q = sys.argv[1], sys.argv[2].lower()      # mode: route | stop
rd = lambda f, **k: pd.read_csv(RAW / f, dtype=str, **k)

routes, trips = rd("routes.txt"), rd("trips.txt")
st = rd("stop_times.txt", usecols=["trip_id", "stop_id"])   # big file, so load two columns only

if mode == "stop":                               # routes that serve a stop whose name contains q
    stops = rd("stops.txt")
    ids = stops[stops.stop_name.str.lower().str.contains(q, na=False, regex=False)].stop_id
    tids = st[st.stop_id.isin(ids)].trip_id.unique()
    rids = trips[trips.trip_id.isin(tids)].route_id.unique()
    cand = routes[routes.route_id.isin(rids)]
else:                                            # routes whose name contains q
    m = routes.route_short_name.str.lower().str.contains(q, na=False, regex=False)
    if "route_long_name" in routes.columns:
        m |= routes.route_long_name.str.lower().str.contains(q, na=False, regex=False)
    cand = routes[m]

counts = st.trip_id.value_counts()
rows = []
for _, r in cand.iterrows():
    t = trips[(trips.route_id == r.route_id) & trips.shape_id.notna()]
    for shape_id, g in t.groupby("shape_id"):
        rows.append((r.route_id, r.route_short_name, shape_id, len(g), int(counts.get(g.trip_id.iloc[0], 0))))
out = pd.DataFrame(rows, columns=["route_id", "name", "shape_id", "trips", "stops"])
print(out.sort_values(["name", "route_id"]).to_string(index=False))