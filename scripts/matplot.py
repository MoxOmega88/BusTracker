import pandas as pd
import matplotlib.pyplot as plt

shapes = pd.read_csv("data/filtered/shapes.txt")
trips = pd.read_csv("data/filtered/trips.txt", dtype=str)
stops = pd.read_csv("data/filtered/stops.txt")

sid = trips.iloc[0]["shape_id"]
line = shapes[shapes["shape_id"].astype(str) == sid]
plt.plot(line["shape_pt_lon"], line["shape_pt_lat"], "-", label="shape")
plt.scatter(stops["stop_lon"], stops["stop_lat"], s=8, c="red", label="stops")
plt.legend(); plt.title(f"shape {sid}"); plt.show()