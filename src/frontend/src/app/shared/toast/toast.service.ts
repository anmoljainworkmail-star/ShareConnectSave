import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

export type ToastKind = 'neutral' | 'success' | 'error';

export interface ToastMessage {
  id: number;
  kind: ToastKind;
  message: string;
  // True during the fade-out, so the host can animate it before removal.
  leaving: boolean;
}

// An in-app banner is a toast with an action: persistent a little longer and
// tappable, used for "someone wants to connect with you".
export interface BannerMessage {
  id: number;
  title: string;
  message: string;
  actionLabel: string;
  actionRoute: unknown[];
  leaving: boolean;
}

// FIGMA_SPEC.md 3.6: auto-dismiss 3s; 300ms slide/fade (tokens.css
// --toast-transition -- keep the two in step).
const TOAST_LIFETIME_MS = 3_000;
const BANNER_LIFETIME_MS = 8_000;
const FADE_OUT_MS = 300;

// Single Responsibility (S) + Interface Segregation (I): "tell the user
// something briefly" is its own tiny capability that Radar (send failures),
// ConnectionFlowService (declined/expired) and the Inbound screen all need
// but none should own. Callers only see show()/showBanner(); they never touch
// a DOM node, a timer, or the host component -- the same reason you hand a
// message to a front desk instead of walking to everyone's office.
//
// State management follows the angular-pwa skill: a service holding
// BehaviorSubjects, rendered with the async pipe -- no NgRx.
@Injectable({ providedIn: 'root' })
export class ToastService {
  private nextId = 1;
  private readonly toastsSubject = new BehaviorSubject<ToastMessage[]>([]);
  private readonly bannersSubject = new BehaviorSubject<BannerMessage[]>([]);

  readonly toasts$: Observable<ToastMessage[]> = this.toastsSubject.asObservable();
  readonly banners$: Observable<BannerMessage[]> = this.bannersSubject.asObservable();

  show(message: string, kind: ToastKind = 'neutral'): void {
    const toast: ToastMessage = { id: this.nextId++, kind, message, leaving: false };
    this.toastsSubject.next([...this.toastsSubject.value, toast]);
    this.scheduleRemoval(toast.id, TOAST_LIFETIME_MS, this.toastsSubject);
  }

  showBanner(banner: Omit<BannerMessage, 'id' | 'leaving'>): void {
    const entry: BannerMessage = { ...banner, id: this.nextId++, leaving: false };
    this.bannersSubject.next([...this.bannersSubject.value, entry]);
    this.scheduleRemoval(entry.id, BANNER_LIFETIME_MS, this.bannersSubject);
  }

  dismissBanner(id: number): void {
    this.removeNow(id, this.bannersSubject);
  }

  // Immutable updates (new array each time): a subscriber holding an earlier
  // emission never sees it mutate under it.
  private scheduleRemoval<T extends { id: number; leaving: boolean }>(
    id: number,
    lifetimeMs: number,
    subject: BehaviorSubject<T[]>
  ): void {
    setTimeout(() => {
      subject.next(subject.value.map(item => (item.id === id ? { ...item, leaving: true } : item)));
      setTimeout(() => this.removeNow(id, subject), FADE_OUT_MS);
    }, lifetimeMs);
  }

  private removeNow<T extends { id: number }>(id: number, subject: BehaviorSubject<T[]>): void {
    subject.next(subject.value.filter(item => item.id !== id));
  }
}
