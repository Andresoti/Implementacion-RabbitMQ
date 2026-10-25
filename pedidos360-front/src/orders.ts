import { Component, DestroyRef, effect, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from './auth';
import { ApiError, OrdersApi, Order, OrderInput, ProcessingResult } from './api';
@Component({selector:'app-orders',imports:[FormsModule],templateUrl:'./orders.html'})
export class OrdersPage implements OnInit {
 private readonly api=inject(OrdersApi);
 readonly auth=inject(AuthService);
 private session=this.auth.user();
 private readonly destroy=inject(DestroyRef);
 private requestContext() {
  const session=this.auth.user();
  const current=()=>!this.destroy.destroyed && session===this.auth.user();
  return {current, report:(error:unknown)=>current() || (!this.destroy.destroyed && !this.auth.user() && error instanceof ApiError && error.status===401)};
 }
 constructor() {
  effect(()=> {
   const session=this.auth.user();
   if (session === this.session) return;
   this.session=session;
   this.orders.set([]); this.results.set([]); this.resultsLoaded.set(false);
   this.input={cliente:'',producto:'',cantidad:1}; this.success.set('');
   this.loading.set(false); this.saving.set(false); this.resultsLoading.set(false);
   if (session) { this.orderError.set(''); this.resultsError.set(''); this.createError.set(''); this.loginError.set(''); }
  });
 }
 readonly orderError=signal('');
 readonly resultsError=signal('');
 readonly loginError=signal('');
 async login():Promise<void> { try { await this.auth.login(); } catch { this.loginError.set('Sign-in could not start. Please try again.'); } }
 input:OrderInput={cliente:'',producto:'',cantidad:1};
 readonly saving=signal(false);
 readonly createError=signal('');
 readonly success=signal('');
 async create():Promise<void> {
  if (this.saving() || this.loading() || !this.auth.hasScope('pedidos/write')) return;
  const context=this.requestContext();
  this.saving.set(true); this.createError.set(''); this.success.set('');
  try {
   const order=await this.api.create(this.input);
   if (!context.current()) return;
   this.orders.update(orders=>[...orders,order]);
   this.input={cliente:'',producto:'',cantidad:1};
   this.success.set(`Order ${order.id} created (${order.estado}).`);
  } catch(error) { if (context.report(error)) this.createError.set(error instanceof Error ? error.message : 'Could not create the order.'); }
  finally { if (context.current()) this.saving.set(false); }
 }
 readonly results=signal<ProcessingResult[]>([]);
 readonly resultsLoading=signal(false);
 readonly resultsLoaded=signal(false);
 async refreshResults():Promise<void> {
  if (this.resultsLoading() || !this.auth.hasScope('pedidos/read')) return;
  const context=this.requestContext();
  this.resultsLoading.set(true); this.resultsError.set('');
  try { const results=await this.api.results(); if (context.current()) { this.results.set(results); this.resultsLoaded.set(true); } }
  catch(error) { if (context.report(error)) { this.results.set([]); this.resultsError.set(error instanceof Error ? error.message : 'Could not load results.'); } }
  finally { if (context.current()) this.resultsLoading.set(false); }
 }
 readonly orders=signal<Order[]>([]);
 readonly loading=signal(false);
 ngOnInit():void { void this.refreshOrders(); }
 async refreshOrders():Promise<void> {
  if (this.loading() || this.saving() || !this.auth.hasScope('pedidos/read')) return;
  const context=this.requestContext();
  this.loading.set(true); this.orderError.set('');
  try { const orders=await this.api.list(); if (context.current()) this.orders.set(orders); }
  catch(error) { if (context.report(error)) { if (error instanceof ApiError && [401,403].includes(error.status)) this.orders.set([]); this.orderError.set(error instanceof Error ? error.message : 'Could not load orders.'); } }
  finally { if (context.current()) this.loading.set(false); }
 }
}
