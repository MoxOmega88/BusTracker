import math
import pandas as pd
import matplotlib.pyplot as plt

shapes = pd.read_csv("data/filtered/shapes.txt", dtype={"shape_id": str})
trips = pd.read_csv("data/filtered/trips.txt", dtype=str)
st = pd.read_csv("data/filtered/stop_times.txt", dtype=str)
stops = pd.read_csv("data/filtered/stops.txt", dtype=str)

reps = trips.drop_duplicates("shape_id")
cols = 4
rows = math.ceil(len(reps) / cols)
fig, axes = plt.subplots(rows, cols, figsize=(16, 4 * rows), squeeze=False)
for ax, (_, t) in zip(axes.flat, reps.iterrows()):
    line = shapes[shapes.shape_id == t.shape_id]
    ts = st[st.trip_id == t.trip_id].merge(stops, on="stop_id")
    ax.plot(line.shape_pt_lon.astype(float), line.shape_pt_lat.astype(float))
    ax.scatter(ts.stop_lon.astype(float), ts.stop_lat.astype(float), s=8, c="red")
    ax.set_title(f"{t.route_id} / {t.shape_id}", fontsize=8)
for ax in list(axes.flat)[len(reps):]:
    ax.axis("off")
plt.tight_layout()
plt.show()