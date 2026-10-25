import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { User } from 'oidc-client-ts';
import { OrdersApi, validateOrder } from './api';
import { CONFIG, parseConfig } from './config';
import { AuthService } from './auth';
import { tokenInterceptor } from './access';
const config=parseConfig({authority:'https://issuer.example',clientId:'public',logoutDomain:'https://login.example',redirectUri:'http://localhost:4200/callback',logoutRedirectUri:'http://localhost:4200/',apiBaseUrl:'http://localhost:8080',workerApiBaseUrl:'http://localhost:8081',scopes:'openid email pedidos/read pedidos/write'});
const order={id:1,cliente:'Ada',producto:'Book',cantidad:2,estado:'CREATED'};
describe('order validation', () => {
 it.each(['\u0000','\ud800','\udc00','a\ud800b'])('rejects database-invalid Unicode %j before POST', value => {
  expect(()=>validateOrder({cliente:value,producto:'Book',cantidad:1})).toThrow(/customer/i);
  expect(()=>validateOrder({cliente:'Ada',producto:value,cantidad:1})).toThrow(/product/i);
 });
 it('trims contract names and converts a whole numeric quantity', () => { expect(validateOrder({cliente:' Ada ',producto:' Book ',cantidad:'2'})).toEqual({cliente:'Ada',producto:'Book',cantidad:2}); });
 it.each([{cliente:' ',producto:'Book',cantidad:1},{cliente:'a'.repeat(101),producto:'Book',cantidad:1},{cliente:'Ada',producto:'a'.repeat(201),cantidad:1},{cliente:'Ada',producto:' ',cantidad:1},...['',0,-1,1.5,10001,'abc'].map(cantidad=>({cliente:'Ada',producto:'Book',cantidad}))])('rejects invalid input %j', input => { expect(()=>validateOrder(input)).toThrow(/customer|product|quantity/i); });
});
describe('orders API', () => {
 beforeEach(()=> { TestBed.configureTestingModule({providers:[{provide:CONFIG,useValue:config},provideHttpClient(withInterceptors([tokenInterceptor])),provideHttpClientTesting()]}); TestBed.inject(AuthService).user.set(new User({access_token:'secret',token_type:'Bearer',scope:'pedidos/read pedidos/write',expires_at:Math.floor(Date.now()/1000)+300,profile:{sub:'subject',iss:config.authority,aud:'public',iat:1,exp:9999999999}})); });
 afterEach(()=>TestBed.inject(HttpTestingController).verify());
 it.each([['list','pedidos/read'],['results','pedidos/read'],['create','pedidos/write']] as const)('requires exact %s scope %s before issuing requests', async(method,scope)=> {
  TestBed.inject(AuthService).user()!.scope='openid pedidos/read-extra pedidos/write-extra';
  const api=TestBed.inject(OrdersApi);
  await expect(method==='create'?api.create({cliente:'Ada',producto:'Book',cantidad:1}):api[method]()).rejects.toThrow(scope);
 });
 it.each([[401,/sign in again/i],[403,/permission/i],[500,/server/i],[0,/network/i]] as const)('maps HTTP %s to a safe actionable error', async(status,message)=> {
  const pending=TestBed.inject(OrdersApi).list(); const assertion=expect(pending).rejects.toThrow(message);
  TestBed.inject(HttpTestingController).expectOne(config.apiBaseUrl+'/api/pedidos').flush('<html>private backend trace</html>',{status,statusText:'Failure'}); await assertion;
 });
 it.each(['<html>not json</html>',{},[null],[{id:'invalid'}]])('rejects malformed order response %j', async body=> {
  const pending=TestBed.inject(OrdersApi).list(); const assertion=expect(pending).rejects.toThrow(/invalid.*response/i);
  TestBed.inject(HttpTestingController).expectOne(config.apiBaseUrl+'/api/pedidos').flush(body); await assertion;
 });
 it('rejects a created response with an unexpected initial state',async()=> {
  const pending=TestBed.inject(OrdersApi).create({cliente:'Ada',producto:'Book',cantidad:2}); const assertion=expect(pending).rejects.toThrow(/CREATED/);
  TestBed.inject(HttpTestingController).expectOne(config.apiBaseUrl+'/api/pedidos').flush({...order,estado:'PENDING'},{status:201,statusText:'Created'}); await assertion;
 });
 it('rejects a create success with the wrong HTTP status', async()=> {
  const pending=TestBed.inject(OrdersApi).create({cliente:'Ada',producto:'Book',cantidad:2}); const assertion=expect(pending).rejects.toThrow(/201/);
  TestBed.inject(HttpTestingController).expectOne(config.apiBaseUrl+'/api/pedidos').flush(order); await assertion;
 });
 it('rejects malformed asynchronous result domains and identifiers', async()=> {
  const pending=TestBed.inject(OrdersApi).results(); const assertion=expect(pending).rejects.toThrow(/invalid.*response/i);
  TestBed.inject(HttpTestingController).expectOne(config.workerApiBaseUrl+'/api/results').flush([{eventId:'not-uuid',orderId:1,domain:'OTHER',result:'x',processedAt:'invalid'}]); await assertion;
 });
 it('posts only the validated Spanish payload and requires HTTP 201', async()=> {
  const pending=TestBed.inject(OrdersApi).create({cliente:' Ada ',producto:' Book ',cantidad:'2'});
  const request=TestBed.inject(HttpTestingController).expectOne(config.apiBaseUrl+'/api/pedidos');
  expect(request.request.method).toBe('POST'); expect(request.request.body).toEqual({cliente:'Ada',producto:'Book',cantidad:2});
  request.flush(order,{status:201,statusText:'Created'}); expect(await pending).toEqual(order);
 });
 it('lists processing results from the independently configured worker', async()=> {
  const result={eventId:'123e4567-e89b-12d3-a456-426614174000',orderId:1,domain:'TICKET',result:'Ticket generated',processedAt:'2026-10-24T12:00:00Z'};
  const pending=TestBed.inject(OrdersApi).results(); const request=TestBed.inject(HttpTestingController).expectOne(config.workerApiBaseUrl+'/api/results');
  expect(request.request.method).toBe('GET'); request.flush([result]); expect(await pending).toEqual([result]);
 });
 it.each(['list','results','create'] as const)('discards late %s responses after an account change without rejecting the new session',async method=> {
  const auth=TestBed.inject(AuthService), api=TestBed.inject(OrdersApi);
  const pending=method==='create'?api.create({cliente:'Ada',producto:'Book',cantidad:1}):api[method]();
  const assertion=expect(pending).rejects.toThrow(/session changed/i);
  const request=TestBed.inject(HttpTestingController).expectOne(method==='results'?config.workerApiBaseUrl+'/api/results':config.apiBaseUrl+'/api/pedidos');
  const next=new User({...auth.user()!,access_token:'next',profile:{...auth.user()!.profile,sub:'next'}}); auth.user.set(next);
  request.flush(method==='create'?order:[],{status:method==='create'?201:200,statusText:'OK'});
  await assertion; expect(auth.user()).toBe(next);
 });
 it('does not clear a new session when an old request returns HTTP 401',async()=> {
  const auth=TestBed.inject(AuthService), pending=TestBed.inject(OrdersApi).list();
  const assertion=expect(pending).rejects.toThrow(/session changed/i);
  const request=TestBed.inject(HttpTestingController).expectOne(config.apiBaseUrl+'/api/pedidos');
  const next=new User({...auth.user()!,access_token:'next'}); auth.user.set(next);
  request.flush('',{status:401,statusText:'Unauthorized'}); await assertion; expect(auth.user()).toBe(next);
 });
 it.each(['list','results','create'] as const)('bounds a stalled %s request and cancels its subscription',async method=> {
  vi.useFakeTimers();
  try {
   const api=TestBed.inject(OrdersApi);
   const pending=method==='create'?api.create({cliente:'Ada',producto:'Book',cantidad:1}):api[method]();
   const assertion=expect(pending).rejects.toThrow(/timed out/i);
   const request=TestBed.inject(HttpTestingController).expectOne(method==='results'?config.workerApiBaseUrl+'/api/results':config.apiBaseUrl+'/api/pedidos');
   await vi.advanceTimersByTimeAsync(15001); await assertion; expect(request.cancelled).toBe(true);
  } finally { vi.useRealTimers(); }
 });
 it.each([0,408,500,201])('warns against duplicate POST retries after ambiguous HTTP %s',async status=> {
  const pending=TestBed.inject(OrdersApi).create({cliente:'Ada',producto:'Book',cantidad:1});
  const assertion=expect(pending).rejects.toThrow(/may have been created.*refresh orders before submitting again/i);
  TestBed.inject(HttpTestingController).expectOne(config.apiBaseUrl+'/api/pedidos').flush(status===201?{}:'',{status,statusText:'Unconfirmed'});
  await assertion;
 });
 it('rejects data that arrives after expiry with an actionable sign-in error',async()=> {
  const auth=TestBed.inject(AuthService), pending=TestBed.inject(OrdersApi).list();
  const assertion=expect(pending).rejects.toThrow(/sign in again/i);
  auth.user()!.expires_at=1;
  TestBed.inject(HttpTestingController).expectOne(config.apiBaseUrl+'/api/pedidos').flush([order]);
  await assertion; expect(auth.user()).toBeNull();
 });
 it.each([{cliente:''},{cliente:'x'.repeat(101)},{producto:'x'.repeat(201)},{cliente:'\u0000'},{producto:'\ud800'},{id:Number.MAX_SAFE_INTEGER+1}])('rejects orders outside the response contract %j',async fields=> {
  const pending=TestBed.inject(OrdersApi).list(); const assertion=expect(pending).rejects.toThrow(/invalid orders response/i);
  TestBed.inject(HttpTestingController).expectOne(config.apiBaseUrl+'/api/pedidos').flush([{...order,...fields}]); await assertion;
 });
 it('lists the Spanish order contract', async()=> {
  const pending=TestBed.inject(OrdersApi).list(); const request=TestBed.inject(HttpTestingController).expectOne(config.apiBaseUrl+'/api/pedidos');
  expect(request.request.method).toBe('GET'); request.flush([order]); expect(await pending).toEqual([order]);
 });
});
