import { afterEach, describe, expect, it, vi } from "vitest";
import { ipcamMaster } from "../src/ipcam";

vi.mock("../src/catalog", () => ({ loadCatalog: () => [{ url: "/v1/ipcam/testcam.m3u8" }] }));

const state = (details: Record<string, string>) => vi.fn(async () => new Response(JSON.stringify({ details })));

afterEach(() => vi.unstubAllGlobals());

describe("ipcamMaster", () => {
  it("points a catalog alias at its current stream over https", async () => {
    vi.stubGlobal("fetch", state({ streamavailable: "1", streamid: "abc123", address: "http://s66.ipcamlive.com/" }));
    expect(await ipcamMaster("testcam")).toBe(
      "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=4000000\nhttps://s66.ipcamlive.com/streams/abc123/stream.m3u8\n",
    );
  });
  it("serves nothing for aliases outside the catalog, offline cams or foreign hosts", async () => {
    vi.stubGlobal("fetch", state({ streamavailable: "1", streamid: "abc123", address: "http://s66.ipcamlive.com/" }));
    expect(await ipcamMaster("someoneelse")).toBeNull();
    vi.stubGlobal("fetch", state({ streamavailable: "0", streamid: "abc123", address: "http://s66.ipcamlive.com/" }));
    expect(await ipcamMaster("testcam")).toBeNull();
    vi.stubGlobal("fetch", state({ streamavailable: "1", streamid: "abc123", address: "http://evil.example/" }));
    expect(await ipcamMaster("testcam")).toBeNull();
  });
});
