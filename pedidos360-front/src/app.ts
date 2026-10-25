import { Component, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { Router, RouterLink, RouterOutlet, Routes } from '@angular/router';
import { AuthService } from './auth';
import { authGuard } from './access';
import { OrdersPage } from './orders';
@Component({selector:'app-root',imports:[RouterOutlet,RouterLink],template:`
 <header><h1>Pedidos360</h1><nav aria-label="Main navigation"><a routerLink="/">Home</a>
 @if (auth.token) { <a routerLink="/orders">Orders</a><button type="button" data-logout (click)="logout()" [disabled]="busy()">Sign out</button> }</nav></header>
 <main>@if (error()) { <p role="alert">{{error()}}</p> }<router-outlet /></main>`})
export class App {
 readonly auth=inject(AuthService);
 readonly busy=signal(false);
 readonly error=signal('');
 async logout():Promise<void> {
  if(this.busy()) return;
  this.busy.set(true); this.error.set('');
  try { await this.auth.logout(); }
  catch { this.error.set('Sign-out could not complete. Your local session has been cleared. Reload and try again.'); }
  finally { this.busy.set(false); }
 }
}
@Component({selector:'app-login',imports:[RouterLink],template:`
 <section aria-labelledby="login-heading"><h2 id="login-heading">Welcome</h2>
 <p>Sign in with Cognito to create orders and view processing results. Registration is available on the hosted sign-in page when enabled by your provider.</p>
 @if (auth.token) { <a routerLink="/orders">Open orders</a> }
 @else { <button type="button" (click)="login()" [disabled]="busy()">{{busy() ? 'Opening sign-in…' : 'Sign in or register'}}</button> }
 @if (error()) { <p role="alert">{{error()}}</p> }</section>`})
export class LoginPage {
 readonly auth=inject(AuthService);
 readonly busy=signal(false);
 readonly error=signal('');
 async login():Promise<void> {
  if (this.busy()) return;
  this.busy.set(true); this.error.set('');
  try { await this.auth.login(); }
  catch { this.error.set('Sign-in could not start. Please try again.'); }
  finally { this.busy.set(false); }
 }
}
@Component({selector:'app-callback',template:`
 <section aria-labelledby="callback-heading"><h2 id="callback-heading">Sign-in callback</h2>
 @if (error()) { <p role="alert">{{error()}}</p><button type="button" (click)="retry()">Sign in again</button> }
 @else { <p role="status">Verifying sign-in…</p> }</section>`})
export class CallbackPage implements OnInit, OnDestroy {
 private readonly auth=inject(AuthService);
 private readonly router=inject(Router);
 private destroyed=false;
 readonly error=signal('');
 ngOnDestroy():void { this.destroyed=true; }
 async ngOnInit():Promise<void> {
  try {
   await this.auth.callback(location.href);
   if (!this.destroyed) await this.router.navigateByUrl('/orders',{replaceUrl:true});
  } catch {
   if (!this.destroyed) this.error.set('Sign-in could not be verified. Please sign in again.');
  }
 }
 async retry():Promise<void> { try { await this.auth.login(); } catch { this.error.set('Sign-in could not start. Please try again.'); } }
}
export const routes:Routes=[
 {path:'',component:LoginPage,pathMatch:'full'},
 {path:'callback',component:CallbackPage},
 {path:'orders',component:OrdersPage,canActivate:[authGuard]},
 {path:'**',redirectTo:''}
];
