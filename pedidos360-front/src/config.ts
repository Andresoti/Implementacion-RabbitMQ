import { InjectionToken } from '@angular/core';
export interface RuntimeConfig { authority: string; clientId: string; logoutDomain: string; redirectUri: string; logoutRedirectUri: string; apiBaseUrl: string; workerApiBaseUrl: string; scopes: string; }
export const CONFIG = new InjectionToken<RuntimeConfig>('Runtime configuration');
export async function loadConfig(fetcher: typeof fetch = fetch):Promise<RuntimeConfig> {
 const controller=new AbortController();
 let timer:ReturnType<typeof setTimeout>;
 const deadline=new Promise<never>((_,reject)=> { timer=setTimeout(()=> { controller.abort(); reject(new Error('Timed out')); },15000); });
 try {
  return await Promise.race([deadline,(async()=> {
   const response=await fetcher('/assets/config.json',{cache:'no-store',credentials:'omit',signal:controller.signal});
   if (!response.ok) throw new Error('Unavailable');
   return parseConfig(await response.json());
  })()]);
 } catch { throw new Error('Startup configuration error. Check /assets/config.json and the public Cognito settings, then reload.'); }
 finally { clearTimeout(timer!); }
}
export function parseConfig(value: unknown): RuntimeConfig {
  const fail = () => { throw new Error('Invalid runtime configuration. Check /assets/config.json and the public Cognito settings.'); };
  if (!value || typeof value !== 'object') return fail();
  const input = value as RuntimeConfig;
  if (typeof input.clientId !== 'string' || !input.clientId.trim() || /[<>\s\u0000-\u001f\u007f]/.test(input.clientId)) return fail();
  if (typeof input.scopes !== 'string' || !['openid','email','pedidos/read','pedidos/write'].every(scope => input.scopes.split(/\s+/).includes(scope))) return fail();
  const result = { ...input, workerApiBaseUrl: input.workerApiBaseUrl === undefined ? input.apiBaseUrl : input.workerApiBaseUrl };
  for (const key of ['authority','logoutDomain','redirectUri','logoutRedirectUri','apiBaseUrl','workerApiBaseUrl'] as const) {
    try {
      if (typeof result[key] !== 'string') return fail();
      const url = new URL(result[key]);
      if ((url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost','127.0.0.1','[::1]'].includes(url.hostname))) || url.username || url.password || url.search || url.hash || /[<>]/.test(result[key])) return fail();
      if (key === 'apiBaseUrl' || key === 'workerApiBaseUrl' || key === 'logoutDomain') result[key] = url.toString().replace(/\/$/, '');
    } catch { return fail(); }
  }
  return result;
}
