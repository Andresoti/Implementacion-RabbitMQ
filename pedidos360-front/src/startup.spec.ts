import { beforeEach, describe, expect, it, vi } from 'vitest';
import { startApplication } from './startup';
describe('application startup',()=> {
 beforeEach(()=> {document.body.innerHTML='<app-root></app-root>';});
 it('loads public configuration before bootstrapping the Angular app',async()=> {
  const config={authority:'https://issuer.example',clientId:'public',logoutDomain:'https://login.example',redirectUri:'http://localhost:4200/callback',logoutRedirectUri:'http://localhost:4200/',apiBaseUrl:'http://localhost:8080',scopes:'openid email pedidos/read pedidos/write'};
  const bootstrap=vi.fn().mockResolvedValue(undefined); const fetcher=vi.fn().mockResolvedValue(new Response(JSON.stringify(config)));
  await startApplication(bootstrap,fetcher); expect(bootstrap).toHaveBeenCalledWith({...config,workerApiBaseUrl:config.apiBaseUrl});
 });
 it('renders a safe accessible startup error instead of bootstrapping invalid config',async()=> {
  const bootstrap=vi.fn(); const fetcher=vi.fn().mockRejectedValue(new Error('<script>secret</script>'));
  await startApplication(bootstrap,fetcher); expect(bootstrap).not.toHaveBeenCalled();
  expect(document.querySelector('[role="alert"]')?.textContent).toContain('Startup configuration error'); expect(document.body.innerHTML).not.toContain('secret');
 });
});
