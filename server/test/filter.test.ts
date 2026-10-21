import { describe, expect, it } from "vitest";
import { heightOf, isScenicTitle, isUsableLive } from "../src/filter";
import { liveVideo } from "./helpers";

describe("isScenicTitle", () => {
  it.each([
    "Jupiter Inlet Live Cam 4K",
    "Miami Beach Boardwalk Webcam",
    "Newsom Street Harbor View", // "news" inside a word is not a match
    "Hitsville Pier Cam",
    "Live from the Keys - Sunset Point",
  ])("keeps %s", (t) => expect(isScenicTitle(t)).toBe(true));

  it.each([
    "WPTV News Live",
    "BREAKING: storm coverage",
    "lofi hip hop radio - beats to relax",
    "24/7 Music Hits Non-Stop",
    "Sunday Worship Service LIVE",
    "Chill Gaming Stream",
    "Just Chatting with viewers",
    "Crypto trading live",
    "ASMR rain sounds",
  ])("drops %s", (t) => expect(isScenicTitle(t)).toBe(false));
});

describe("isUsableLive", () => {
  it("keeps a live, embeddable, public, scenic stream", () => {
    expect(isUsableLive(liveVideo("a"))).toBe(true);
  });
  it("drops ended streams", () => {
    expect(isUsableLive(liveVideo("a", { liveStreamingDetails: { actualStartTime: "x", actualEndTime: "y" } }))).toBe(false);
  });
  it("drops streams that have not started", () => {
    expect(isUsableLive(liveVideo("a", { liveStreamingDetails: {} }))).toBe(false);
    expect(isUsableLive(liveVideo("a", { liveStreamingDetails: undefined }))).toBe(false);
  });
  it("drops non-embeddable and non-public", () => {
    expect(isUsableLive(liveVideo("a", { status: { embeddable: false } }))).toBe(false);
    expect(isUsableLive(liveVideo("a", { status: {} }))).toBe(false);
    expect(isUsableLive(liveVideo("a", { status: { embeddable: true, privacyStatus: "unlisted" } }))).toBe(false);
  });
  it("drops liveBroadcastContent != live", () => {
    const v = liveVideo("a");
    v.snippet.liveBroadcastContent = "none";
    expect(isUsableLive(v)).toBe(false);
  });
  it("drops non-scenic titles", () => {
    const v = liveVideo("a");
    v.snippet.title = "Local News Live";
    expect(isUsableLive(v)).toBe(false);
  });
});

describe("heightOf", () => {
  it("maps definition and the fhd/qhd/uhd thumbnail keys", () => {
    expect(heightOf(liveVideo("a", { contentDetails: { definition: "sd" } }))).toBe(480);
    expect(heightOf(liveVideo("a"))).toBe(1080);
    expect(heightOf(liveVideo("a", { snippet: { thumbnails: { fhd: {} } } }))).toBe(1080);
    expect(heightOf(liveVideo("a", { snippet: { thumbnails: { qhd: {} } } }))).toBe(1440);
    expect(heightOf(liveVideo("a", { snippet: { thumbnails: { uhd: {}, qhd: {} } } }))).toBe(2160);
    expect(heightOf(liveVideo("a", { contentDetails: {} }))).toBe(0);
  });
});
