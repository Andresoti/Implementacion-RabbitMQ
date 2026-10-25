import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { User } from 'oidc-client-ts';
import { OrdersPage } from './orders';
import { CONFIG, parseConfig } from './config';
import { AuthService } from './auth';
import { tokenInterceptor } from './access';
const config=parseConfig({authority:'https://issuer.example',clientId:'public',logoutDomain:'https://login.example',redirectUri:'http://localhost:4200/callback',logoutRedirectUri:'http://localhost:4200/',apiBaseUrl:'http://localhost:8080',workerApiBaseUrl:'http://localhost:8081',scopes:'openid email pedidos/read pedidos/write'});
const order={id:1,cliente:'Ada',producto:'Book',cantidad:2,estado:'CREATED'};
describe('orders page',()=> {
 let fixture:ComponentFixture<OrdersPage>, http:HttpTestingController;
 beforeEach(()=> { TestBed.configureTestingModule({providers:[{provide:CONFIG,useValue:config},provideHttpClient(withInterceptors([tokenInterceptor])),provideHttpClientTesting()]}); TestBed.inject(AuthService).user.set(new User({access_token:'secret',token_type:'Bearer',scope:'pedidos/read pedidos/write',expires_at:Math.floor(Date.now()/1000)+300,profile:{sub:'subject',iss:config.authority,aud:'public',iat:1,exp:9999999999}})); fixture=TestBed.createComponent(OrdersPage); http=TestBed.inject(HttpTestingController); });
 afterEach(()=>http.verify());
 it('disables unauthorized actions with exact permission guidance without issuing requests',async()=> {
  TestBed.inject(AuthService).user()!.scope='pedidos/read-extra pedidos/write-extra';
  fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
  expect(fixture.nativeElement.textContent).toContain('pedidos/read'); expect(fixture.nativeElement.textContent).toContain('pedidos/write');
  expect(fixture.nativeElement.querySelector('fieldset').disabled).toBe(true); http.expectNone(config.apiBaseUrl+'/api/pedidos');
 });
 it.each([[401,'sign in again'],[403,'permission'],[500,'server']] as const)('shows a safe list error for HTTP %s',async(status,text)=> {
  fixture.detectChanges(); http.expectOne(config.apiBaseUrl+'/api/pedidos').flush('<html>trace</html>',{status,statusText:'Failed'});
  await new Promise<void>(resolve=>queueMicrotask(resolve)); await fixture.whenStable(); fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(text);
  expect(fixture.nativeElement.textContent).not.toContain('trace');
  if(status===401) expect(fixture.nativeElement.querySelector('[data-login]')).not.toBeNull();
 });
 it('keeps the order list usable when processing results fail',async()=> {
  fixture.detectChanges(); http.expectOne(config.apiBaseUrl+'/api/pedidos').flush([order]); await new Promise<void>(resolve=>queueMicrotask(resolve)); await fixture.whenStable(); fixture.detectChanges();
  const button=Array.from(fixture.nativeElement.querySelectorAll('button')).find((b:any)=>b.textContent.includes('Refresh results')) as HTMLButtonElement;
  button.click(); http.expectOne(config.workerApiBaseUrl+'/api/results').flush('denied',{status:403,statusText:'Forbidden'});
  await new Promise<void>(resolve=>queueMicrotask(resolve)); await fixture.whenStable(); fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('permission'); expect(fixture.nativeElement.textContent).toContain('Ada'); expect(button.disabled).toBe(false);
 });
 it('refreshes asynchronous results independently and renders all contract fields',async()=> {
  fixture.detectChanges(); http.expectOne(config.apiBaseUrl+'/api/pedidos').flush([]); await new Promise<void>(resolve=>queueMicrotask(resolve)); await fixture.whenStable(); fixture.detectChanges();
  const refresh=Array.from(fixture.nativeElement.querySelectorAll('button')).find((b:any)=>b.textContent.includes('Refresh results')) as HTMLButtonElement;
  expect(refresh).toBeDefined(); refresh.click(); fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('Loading results');
  http.expectOne(config.workerApiBaseUrl+'/api/results').flush([{eventId:'123e4567-e89b-12d3-a456-426614174000',orderId:1,domain:'DOCUMENT',result:'Document generated',processedAt:'2026-10-24T12:00:00Z'}]);
  await new Promise<void>(resolve=>queueMicrotask(resolve)); await fixture.whenStable(); fixture.detectChanges();
  for(const text of ['123e4567-e89b-12d3-a456-426614174000','DOCUMENT','Document generated','2026-10-24T12:00:00Z']) expect(fixture.nativeElement.textContent).toContain(text);
  refresh.click(); http.expectOne(config.workerApiBaseUrl+'/api/results').flush([]); await new Promise<void>(resolve=>queueMicrotask(resolve)); await fixture.whenStable(); fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('No processing results yet');
 });
 it('validates, creates once during an in-flight submit and appends the new order',async()=> {
  fixture.detectChanges(); http.expectOne(config.apiBaseUrl+'/api/pedidos').flush([]); await new Promise<void>(resolve=>queueMicrotask(resolve)); await fixture.whenStable(); fixture.detectChanges();
  const form=fixture.nativeElement.querySelector('form') as HTMLFormElement;
  expect(form).not.toBeNull();
  form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})); await new Promise<void>(resolve=>queueMicrotask(resolve)); await fixture.whenStable(); fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Customer');
  for (const [name,value] of Object.entries({cliente:' Ada ',producto:' Book ',cantidad:'2'})) { const input=form.querySelector(`[name="${name}"]`) as HTMLInputElement; input.value=value; input.dispatchEvent(new Event('input',{bubbles:true})); }
  form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})); form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})); fixture.detectChanges();
  expect(fixture.nativeElement.textContent).toContain('Creating order');
  const request=http.expectOne(config.apiBaseUrl+'/api/pedidos'); expect(request.request.body).toEqual({cliente:'Ada',producto:'Book',cantidad:2});
  request.flush(order,{status:201,statusText:'Created'}); await new Promise<void>(resolve=>queueMicrotask(resolve)); await fixture.whenStable(); fixture.detectChanges();
  expect(fixture.nativeElement.textContent).toContain('Order 1 created (CREATED)'); expect(fixture.nativeElement.querySelector('tbody').textContent).toContain('Ada');
  await fixture.whenStable(); fixture.detectChanges();
  expect((form.querySelector('[name="cliente"]') as HTMLInputElement).value).toBe('');
 });
 it.each(['logout','account change'] as const)('clears private page state on %s',async change=> {
  fixture.detectChanges(); http.expectOne(config.apiBaseUrl+'/api/pedidos').flush([order]);
  await fixture.whenStable();
  const page=fixture.componentInstance, auth=TestBed.inject(AuthService);
  page.input={cliente:'Private',producto:'Draft',cantidad:1}; page.success.set('Private success');
  page.results.set([{eventId:'123e4567-e89b-12d3-a456-426614174000',orderId:1,domain:'TICKET',result:'Private',processedAt:'2026-10-24T12:00:00Z'}]);
  auth.user.set(change==='logout'?null:new User({...auth.user()!,access_token:'next'}));
  await fixture.whenStable(); fixture.detectChanges();
  expect(page.orders()).toEqual([]); expect(page.results()).toEqual([]);
  expect(page.input.cliente).toBe(''); expect(page.success()).toBe('');
 });
 it.each(['orders','results','create'] as const)('does not let a late %s request change the new account UI',async action=> {
  fixture.detectChanges(); http.expectOne(config.apiBaseUrl+'/api/pedidos').flush([]); await new Promise<void>(resolve=>queueMicrotask(resolve)); await fixture.whenStable();
  const page=fixture.componentInstance, auth=TestBed.inject(AuthService);
  page.input={cliente:'Ada',producto:'Book',cantidad:2};
  const run=()=>action==='orders'?page.refreshOrders():action==='results'?page.refreshResults():page.create();
  const pending=run(); const old=http.expectOne(action==='results'?config.workerApiBaseUrl+'/api/results':config.apiBaseUrl+'/api/pedidos');
  auth.user.set(new User({...auth.user()!,access_token:'next'})); await fixture.whenStable();
  page.input={cliente:'New',producto:'Draft',cantidad:1};
  const next=run(); const current=http.expectOne(action==='results'?config.workerApiBaseUrl+'/api/results':config.apiBaseUrl+'/api/pedidos');
  old.flush('',{status:500,statusText:'Failed'}); await pending;
  expect(page.orderError()).toBe(''); expect(page.resultsError()).toBe(''); expect(page.createError()).toBe('');
  expect(action==='orders'?page.loading():action==='results'?page.resultsLoading():page.saving()).toBe(true);
  current.flush(action==='create'?order:[],{status:action==='create'?201:200,statusText:'OK'}); await next;
 });
 it('keeps a successfully created order visible after list failures',async()=> {
  fixture.detectChanges(); http.expectOne(config.apiBaseUrl+'/api/pedidos').flush('',{status:500,statusText:'Failed'});
  await new Promise<void>(resolve=>queueMicrotask(resolve)); await fixture.whenStable();
  const page=fixture.componentInstance; page.input={cliente:'Ada',producto:'Book',cantidad:2};
  const pending=page.create(); http.expectOne(config.apiBaseUrl+'/api/pedidos').flush(order,{status:201,statusText:'Created'}); await pending; fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('tbody')?.textContent).toContain('Ada');
  const refresh=page.refreshOrders(); http.expectOne(config.apiBaseUrl+'/api/pedidos').flush('',{status:500,statusText:'Failed'}); await refresh; fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('tbody')?.textContent).toContain('Ada');
  expect(page.orderError()).toContain('server');
 });
 it('shows loading, lists orders in an accessible table and supports an empty refresh',async()=> {
  fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('Loading orders');
  http.expectOne(config.apiBaseUrl+'/api/pedidos').flush([order]); await new Promise<void>(resolve => queueMicrotask(resolve)); await fixture.whenStable(); fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('table caption').textContent).toContain('Order list'); expect(fixture.nativeElement.textContent).toContain('Ada');
  const refresh=Array.from(fixture.nativeElement.querySelectorAll('button')).find((b:any)=>b.textContent.includes('Refresh orders')) as HTMLButtonElement;
  refresh.click(); fixture.detectChanges(); http.expectOne(config.apiBaseUrl+'/api/pedidos').flush([]); await new Promise<void>(resolve => queueMicrotask(resolve)); await fixture.whenStable(); fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('No orders yet');
 });
});
