import { describe, it, expect, vi } from 'vitest';
import { loadConfig, parseConfig } from './config';
export const config = { authority:'https://cognito-idp.us-east-1.amazonaws.com/pool', clientId:'public-client', logoutDomain:'https://example.auth.us-east-1.amazoncognito.com', redirectUri:'http://localhost:4200/callback', logoutRedirectUri:'http://localhost:4200/', apiBaseUrl:'http://localhost:8080', scopes:'openid email pedidos/read pedidos/write' };
describe('runtime configuration', () => {
 it('loads public runtime config without caching and reports missing/non-JSON files safely',async()=> {
  const fetcher=vi.fn().mockResolvedValue(new Response(JSON.stringify(config),{status:200}));
  expect((await loadConfig(fetcher)).clientId).toBe(config.clientId); expect(fetcher).toHaveBeenCalledWith('/assets/config.json',{cache:'no-store',credentials:'omit',signal:expect.any(AbortSignal)});
  fetcher.mockResolvedValue(new Response('<html>missing</html>',{status:404})); await expect(loadConfig(fetcher)).rejects.toThrow(/configuration/i);
  fetcher.mockResolvedValue(new Response('<html>SPA fallback</html>',{status:200})); await expect(loadConfig(fetcher)).rejects.toThrow(/configuration/i);
 });
 it.each([null, {}, {...config, authority:'http://remote.example'}, {...config, clientId:''}, {...config, scopes:'openid pedidos/read'}, {...config, apiBaseUrl:'https://api.example/?token=x'}, {...config, redirectUri:'javascript:alert(1)'}])('rejects incomplete or unsafe configuration %j', value => { expect(() => parseConfig(value)).toThrow(/configuration/i); });
 it.each(['fetch','body'])('bounds startup when configuration %s stalls',async phase=> {
  vi.useFakeTimers();
  try {
   const fetcher=vi.fn().mockImplementation(()=>phase==='fetch'?new Promise(()=>{}):Promise.resolve({ok:true,json:()=>new Promise(()=>{})}));
   const pending=loadConfig(fetcher); const assertion=expect(pending).rejects.toThrow(/configuration/i);
   await vi.advanceTimersByTimeAsync(15001); await assertion;
   expect(fetcher.mock.calls[0][1].signal.aborted).toBe(true);
  } finally { vi.useRealTimers(); }
 });
 it.each([{...config,authority:[config.authority]},{...config,redirectUri:[config.redirectUri]},{...config,workerApiBaseUrl:false},{...config,workerApiBaseUrl:null},{...config,clientId:'public\u0000client'}])('rejects malformed public setting types %j',value=> {
  expect(()=>parseConfig(value)).toThrow(/configuration/i);
 });
 it('preserves registered redirect URI trailing slashes', () => { expect(parseConfig(config).logoutRedirectUri).toBe(config.logoutRedirectUri); });
 it('accepts an independent loopback worker', () => { expect(parseConfig({...config,workerApiBaseUrl:'http://localhost:8081'}).workerApiBaseUrl).toBe('http://localhost:8081'); });
 it('validates public settings and defaults the worker to the gateway', () => { expect(parseConfig(config).workerApiBaseUrl).toBe(config.apiBaseUrl); });
});
