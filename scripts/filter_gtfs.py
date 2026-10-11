import pandas as pd
from pathlib import Path

RAW = Path("data/raw")
OUT = Path("data/filtered")
OUT.mkdir(parents=True, exist_ok=True)

CHOSEN_IDS = ["7857", "1037", "4995", "4754"]  # edit if any were missing
TRIPS_PER_ROUTE = 10

routes = pd.read_csv(RAW / "routes.txt", dtype=str)
trips = pd.read_csv(RAW / "trips.txt", dtype=str)
stop_times = pd.read_csv(RAW / "stop_times.txt", dtype=str)
shapes = pd.read_csv(RAW / "shapes.txt", dtype=str)
stops = pd.read_csv(RAW / "stops.txt", dtype=str)

routes_f = routes[routes["route_id"].isin(CHOSEN_IDS)]
print("Routes found:", routes_f[["route_id", "route_short_name"]].values.tolist())

trips_f = trips[trips["route_id"].isin(routes_f["route_id"])]
trips_f = trips_f.dropna(subset=["shape_id"])
trips_f = trips_f.groupby("route_id").head(TRIPS_PER_ROUTE)
print("Trips per route:\n", trips_f.groupby("route_id").size())

stop_times_f = stop_times[stop_times["trip_id"].isin(trips_f["trip_id"])].copy()
shapes_f = shapes[shapes["shape_id"].isin(trips_f["shape_id"])].copy()
stops_f = stops[stops["stop_id"].isin(stop_times_f["stop_id"])]

stop_times_f["stop_sequence"] = stop_times_f["stop_sequence"].astype(int)
stop_times_f = stop_times_f.sort_values(["trip_id", "stop_sequence"])
shapes_f["shape_pt_sequence"] = shapes_f["shape_pt_sequence"].astype(int)
shapes_f = shapes_f.sort_values(["shape_id", "shape_pt_sequence"])

routes_f.to_csv(OUT / "routes.txt", index=False)
trips_f.to_csv(OUT / "trips.txt", index=False)
stop_times_f.to_csv(OUT / "stop_times.txt", index=False)
shapes_f.to_csv(OUT / "shapes.txt", index=False)
stops_f.to_csv(OUT / "stops.txt", index=False)

print(f"DONE trips={len(trips_f)} stop_times={len(stop_times_f)} "
      f"shape_points={len(shapes_f)} stops={len(stops_f)}")