import { stats, useMesh } from '../src/store/useMesh';

describe('mesh store', () => {
  test('refreshState loads native state; stats never invented', async () => {
    expect(stats(null).connected).toBe(0);
    await useMesh.getState().refreshState();
    const s = useMesh.getState();
    expect(s.ready).toBe(true);
    expect(s.state?.profile?.name).toBe('Asha');
    expect(stats(s.state).connected).toBe(1);
  });

  test('refreshState retries while storage is opening', async () => {
    const { NativeModules } = require('react-native');
    const real = NativeModules.OfflineMesh.getState;
    let n = 0;
    NativeModules.OfflineMesh.getState = jest.fn(async () => {
      if (n++ < 2) { const e: any = new Error('opening'); e.code = 'E_NOT_READY'; throw e; }
      return real();
    });
    await useMesh.getState().refreshState();
    expect(n).toBe(3);
    expect(useMesh.getState().error).toBeNull();
    NativeModules.OfflineMesh.getState = real;
  });

  // Regression: with the BLE service off, native nearby() used to return [] and HomeScreen crashed on people.length.
  test.each([[[]], [null], [{}], [{ people: null, devices: undefined }]])('refreshNearby normalizes malformed reply %p', async reply => {
    const { NativeModules } = require('react-native');
    const real = NativeModules.OfflineMesh.nearby;
    NativeModules.OfflineMesh.nearby = jest.fn(async () => reply);
    await useMesh.getState().refreshNearby();
    const n = useMesh.getState().nearby!;
    expect(n.people).toEqual([]);
    expect(n.devices).toEqual([]);
    expect(n.links).toBe(0);
    NativeModules.OfflineMesh.nearby = real;
  });
});
