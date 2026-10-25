import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideHttpClient } from '@angular/common/http';
import { User, UserManager } from 'oidc-client-ts';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { App, CallbackPage, LoginPage, routes } from './app';
import { AuthService } from './auth';
import { CONFIG, parseConfig } from './config';
const config=parseConfig({authority:'https://issuer.example',clientId:'public',logoutDomain:'https://login.example',redirectUri:'http://localhost:4200/callback',logoutRedirectUri:'http://localhost:4200/',apiBaseUrl:'http://localhost:8080',scopes:'openid email pedidos/read pedidos/write'});
describe('application routing',()=> {
 beforeEach(()=>TestBed.configureTestingModule({providers:[{provide:CONFIG,useValue:config},provideRouter(routes),provideHttpClient(),provideHttpClientTesting()]}));
 it('exposes an accessible application title and awaits logout with error feedback',async()=> {
  const auth=TestBed.inject(AuthService); const logout=vi.spyOn(auth,'logout').mockRejectedValue(new Error('private'));
  auth.user.set(new User({access_token:'test',token_type:'Bearer',expires_at:Math.floor(Date.now()/1000)+300,profile:{sub:'subject',iss:config.authority,aud:'public',iat:1,exp:9999999999}}));
  const fixture=TestBed.createComponent(App); fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Pedidos360');
  fixture.nativeElement.querySelector('[data-logout]').click(); await fixture.whenStable(); fixture.detectChanges();
  expect(logout).toHaveBeenCalledOnce(); expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Sign-out could not complete');
 });
 it('protects the order route and provides real hosted sign-in/registration',async()=> {
  const login=vi.spyOn(TestBed.inject(AuthService),'login').mockResolvedValue();
  const harness=await RouterTestingHarness.create('/orders');
  expect(TestBed.inject(Router).url).toBe('/'); expect(harness.routeNativeElement!.textContent).toContain('Sign in or register');
  harness.routeNativeElement!.querySelector('button')!.click(); await harness.fixture.whenStable(); expect(login).toHaveBeenCalledOnce();
 });
 it('routes a verified callback to protected orders without retaining the callback URL',async()=> {
  const auth=TestBed.inject(AuthService);
  vi.spyOn(auth,'callback').mockImplementation(async()=> { auth.user.set(new User({access_token:'test',token_type:'Bearer',scope:'pedidos/read',expires_at:Math.floor(Date.now()/1000)+300,profile:{sub:'subject',iss:config.authority,aud:'public',iat:1,exp:9999999999}})); });
  const harness=await RouterTestingHarness.create('/callback?code=secret&state=transaction');
  await new Promise<void>(resolve=>queueMicrotask(resolve)); await harness.fixture.whenStable(); harness.detectChanges();
  expect(TestBed.inject(Router).url).toBe('/orders');
  TestBed.inject(HttpTestingController).expectOne(config.apiBaseUrl+'/api/pedidos').flush([]);
  await new Promise<void>(resolve=>queueMicrotask(resolve)); await harness.fixture.whenStable(); harness.detectChanges();
  expect(harness.routeNativeElement!.textContent).toContain('No orders yet');
 });
 it('restores the sign-in button with a recoverable error after a real service timeout',async()=> {
  vi.useFakeTimers();
  const redirect=vi.spyOn(UserManager.prototype,'signinRedirect').mockImplementation(()=>new Promise(()=>{}));
  try {
   const fixture=TestBed.createComponent(LoginPage); fixture.detectChanges();
   const button=fixture.nativeElement.querySelector('button') as HTMLButtonElement;
   button.click(); fixture.detectChanges(); expect(button.disabled).toBe(true);
   await vi.advanceTimersByTimeAsync(14999); fixture.detectChanges(); expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
   await vi.advanceTimersByTimeAsync(1); fixture.detectChanges();
   expect(button.disabled).toBe(false); expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Please try again');
   redirect.mockResolvedValue(); button.click(); await vi.advanceTimersByTimeAsync(0); fixture.detectChanges();
   expect(redirect).toHaveBeenCalledTimes(2); expect(button.disabled).toBe(false);
   expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  } finally { redirect.mockRestore(); vi.useRealTimers(); }
 });
 it('shows callback recovery and cleans secrets at the deadline even when library cleanup hangs',async()=> {
  vi.useFakeTimers();
  const callback=vi.spyOn(UserManager.prototype,'signinRedirectCallback').mockImplementation(()=>new Promise(()=>{}));
  const remove=vi.spyOn(UserManager.prototype,'removeUser').mockImplementation(()=>new Promise(()=>{}));
  const replace=vi.spyOn(history,'replaceState'); const navigate=vi.spyOn(TestBed.inject(Router),'navigateByUrl');
  try {
   const fixture=TestBed.createComponent(CallbackPage); fixture.detectChanges();
   expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('Verifying');
   await vi.advanceTimersByTimeAsync(15000); fixture.detectChanges();
   expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();
   expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Please sign in again');
   expect(fixture.nativeElement.querySelector('button').textContent).toBe('Sign in again');
   expect(replace).toHaveBeenCalledWith({},document.title,'/callback'); expect(navigate).not.toHaveBeenCalled();
  } finally { callback.mockRestore(); remove.mockRestore(); replace.mockRestore(); navigate.mockRestore(); vi.useRealTimers(); }
 });
 it('does not redirect after the callback component is destroyed',async()=> {
  let finish!:()=>void;
  const callback=vi.spyOn(TestBed.inject(AuthService),'callback').mockImplementation(()=>new Promise<void>(resolve=>{finish=resolve;}));
  const navigate=vi.spyOn(TestBed.inject(Router),'navigateByUrl').mockResolvedValue(true);
  try {
   const fixture=TestBed.createComponent(CallbackPage); fixture.detectChanges();
   expect(callback).toHaveBeenCalledOnce();
   fixture.destroy(); finish();
   await Promise.resolve(); await Promise.resolve();
   expect(navigate).not.toHaveBeenCalled();
  } finally { callback.mockRestore(); navigate.mockRestore(); }
 });
 it('cleans and rejects an unverified callback with a visible retry option',async()=> {
  const callback=vi.spyOn(TestBed.inject(AuthService),'callback').mockRejectedValue(new Error('private details'));
  const harness=await RouterTestingHarness.create('/callback?code=bad&state=unknown'); await harness.fixture.whenStable(); harness.detectChanges();
  expect(callback).toHaveBeenCalledOnce(); expect(harness.routeNativeElement!.querySelector('[role="alert"]')!.textContent).toContain('Sign-in could not be verified');
  expect(harness.routeNativeElement!.textContent).not.toContain('private details'); expect(harness.routeNativeElement!.querySelector('button')).not.toBeNull();
 });
});
