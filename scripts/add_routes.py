import pandas as pd
from pathlib import Path

RAW, OUT = Path("data/raw"), Path("data/filtered")
NEW_IDS = ["1039", "1221", "2200", "1387", "993", "1070"]
TRIPS_PER_SHAPE = 3          # trips per direction

rd = lambda p: pd.read_csv(p, dtype=str)
routes, trips = rd(RAW / "routes.txt"), rd(RAW / "trips.txt")
stop_times, shapes, stops = rd(RAW / "stop_times.txt"), rd(RAW / "shapes.txt"), rd(RAW / "stops.txt")

r = routes[routes.route_id.isin(NEW_IDS)]
t = trips[trips.route_id.isin(r.route_id)].dropna(subset=["shape_id"])
t = t.groupby("shape_id").head(TRIPS_PER_SHAPE)
print(t.groupby(["route_id", "shape_id"]).size())     # check every route shows up

st = stop_times[stop_times.trip_id.isin(t.trip_id)]
sh = shapes[shapes.shape_id.isin(t.shape_id)]
sp = stops[stops.stop_id.isin(st.stop_id)]

def merge(name, new, keys):
    old = rd(OUT / name)
    pd.concat([old, new], ignore_index=True).drop_duplicates(subset=keys).to_csv(OUT / name, index=False)

merge("routes.txt", r, ["route_id"])
merge("trips.txt", t, ["trip_id"])
merge("stop_times.txt", st, ["trip_id", "stop_sequence"])
merge("shapes.txt", sh, ["shape_id", "shape_pt_sequence"])
merge("stops.txt", sp, ["stop_id"])
print("done")