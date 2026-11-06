import { Injectable } from '@angular/core';

// Two short rising tones, ~0.3 s total. Synthesized with the Web Audio API
// rather than shipping an .mp3: no binary asset to cache in the service
// worker, no extra network fetch on the exact moment a request arrives, and
// nothing to license.
const TONE_FREQUENCIES_HZ = [660, 880] as const;
const TONE_DURATION_S = 0.14;
const TONE_GAIN = 0.15;

// Single Responsibility (S): "make the alert sound" is isolated here so
// ConnectionFlowService just says playAlert() and a future change (a user
// mute setting, a different sound, haptics) touches one file.
@Injectable({ providedIn: 'root' })
export class NotificationSoundService {
  private audioContext: AudioContext | null = null;

  // Best-effort by design. Browser autoplay policy blocks audio until the user
  // has interacted with the page at least once, and some browsers have no
  // AudioContext at all -- in both cases the in-app banner (already shown) is
  // the real notification and the sound is a bonus, so every failure path
  // returns silently instead of throwing into the event stream.
  playAlert(): void {
    const context = this.getContext();
    if (!context) {
      return;
    }

    // Guard clause: a context created before any user gesture starts
    // 'suspended'; resume() only succeeds after a gesture. If it is still
    // suspended afterwards, skip rather than queue tones that fire late.
    void context
      .resume()
      .then(() => {
        if (context.state !== 'running') {
          return;
        }
        this.scheduleTones(context);
      })
      .catch(() => undefined);
  }

  private getContext(): AudioContext | null {
    if (this.audioContext) {
      return this.audioContext;
    }
    // Feature detection over user-agent sniffing (angular-pwa skill).
    if (typeof AudioContext === 'undefined') {
      return null;
    }
    try {
      this.audioContext = new AudioContext();
    } catch {
      return null;
    }
    return this.audioContext;
  }

  private scheduleTones(context: AudioContext): void {
    TONE_FREQUENCIES_HZ.forEach((frequency, index) => {
      const startAt = context.currentTime + index * TONE_DURATION_S;
      const oscillator = context.createOscillator();
      const gain = context.createGain();

      oscillator.type = 'sine';
      oscillator.frequency.value = frequency;
      // Ramp the gain to ~0 instead of cutting the oscillator off: an abrupt
      // stop causes an audible click.
      gain.gain.setValueAtTime(TONE_GAIN, startAt);
      gain.gain.exponentialRampToValueAtTime(0.0001, startAt + TONE_DURATION_S);

      oscillator.connect(gain).connect(context.destination);
      oscillator.start(startAt);
      oscillator.stop(startAt + TONE_DURATION_S);
    });
  }
}
