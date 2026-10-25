import { loadConfig, RuntimeConfig } from './config';
export async function startApplication(bootstrap:(config:RuntimeConfig)=>Promise<unknown>, fetcher:typeof fetch=fetch):Promise<void> {
 try { await bootstrap(await loadConfig(fetcher)); }
 catch {
  const target=document.querySelector('app-root');
  if (target) {
   const heading=document.createElement('h1'); heading.textContent='Pedidos360';
   const message=document.createElement('p'); message.setAttribute('role','alert');
   message.textContent='Startup configuration error. Check /assets/config.json and the public Cognito settings, then reload.';
   target.replaceChildren(heading,message);
  }
 }
}
