import pandas as pd
import matplotlib.pyplot as plt

shapes = pd.read_csv("data/filtered/shapes.txt", dtype={"shape_id": str})
trips = pd.read_csv("data/filtered/trips.txt", dtype=str)
st = pd.read_csv("data/filtered/stop_times.txt", dtype=str)
stops = pd.read_csv("data/filtered/stops.txt", dtype=str)

# one trip per shape_id (i.e. per route direction)
reps = trips.drop_duplicates("shape_id")
fig, axes = plt.subplots(2, 4, figsize=(18, 8))
for ax, (_, t) in zip(axes.flat, reps.iterrows()):
    line = shapes[shapes["shape_id"] == t["shape_id"]]
    trip_stops = st[st["trip_id"] == t["trip_id"]].merge(stops, on="stop_id")
    ax.plot(line["shape_pt_lon"].astype(float), line["shape_pt_lat"].astype(float))
    ax.scatter(trip_stops["stop_lon"].astype(float),
               trip_stops["stop_lat"].astype(float), s=8, c="red")
    ax.set_title(f"{t['route_id']} / {t['shape_id']}")
plt.tight_layout(); plt.show()