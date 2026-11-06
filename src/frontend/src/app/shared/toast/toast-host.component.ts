import { Component } from '@angular/core';
import { Observable } from 'rxjs';

import { BannerMessage, ToastMessage, ToastService } from './toast.service';

// Presentational half of the toast feature (container/presentational split):
// renders whatever ToastService currently holds and owns no logic of its own.
// Mounted exactly once, in AppComponent, so a toast raised from any screen --
// or from a service with no screen at all, like ConnectionFlowService -- shows
// in the same place. That is why it lives in SharedModule rather than in any
// one feature.
@Component({
  selector: 'app-toast-host',
  templateUrl: './toast-host.component.html',
  styleUrl: './toast-host.component.css',
  // Module-based project (no standalone components) -- see UserCardComponent.
  standalone: false
})
export class ToastHostComponent {
  readonly toasts$: Observable<ToastMessage[]>;
  readonly banners$: Observable<BannerMessage[]>;

  constructor(private readonly toastService: ToastService) {
    this.toasts$ = toastService.toasts$;
    this.banners$ = toastService.banners$;
  }

  dismissBanner(banner: BannerMessage): void {
    this.toastService.dismissBanner(banner.id);
  }

  trackById(_index: number, item: ToastMessage | BannerMessage): number {
    return item.id;
  }
}
