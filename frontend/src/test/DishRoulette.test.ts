import { describe, it, expect, afterEach, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import DishRoulette from '../components/food/DishRoulette.vue';
import type { Dish } from '../data';

function makeDish(id: number, slug: string, name: string): Dish {
  return {
    id,
    slug,
    name,
    summary: `${name}的简介`,
    category: '家常菜',
    imageUrl: `/food/${slug}.jpg`,
    imageAlt: name,
    prepMinutes: 20,
    difficulty: '家常',
    rating: 4.5,
    featured: false,
    published: true,
    displayOrder: 0,
    favoriteCount: 0,
    ingredients: ['食材'],
    steps: ['做'],
    createdAt: '2026-06-01T00:00:00Z',
    updatedAt: '2026-06-01T00:00:00Z',
  };
}

const pool = [makeDish(1, 'dish-a', '菜A'), makeDish(2, 'dish-b', '菜B'), makeDish(3, 'dish-c', '菜C')];

let wrapper: VueWrapper | null = null;

async function mountRoulette(props: { dishes?: Dish[]; rng?: () => number } = {}) {
  wrapper = mount(DishRoulette, {
    props: { dishes: props.dishes ?? pool, rng: props.rng },
    attachTo: document.body,
  });
  await flushPromises();
  return wrapper;
}

const dialog = () => document.body.querySelector('.roulette-dialog');
const spinButton = () => document.body.querySelector<HTMLButtonElement>('.roulette-spin')!;
const resultCard = () => document.body.querySelector('.roulette-result');

async function settle() {
  await vi.advanceTimersByTimeAsync(2000);
  await flushPromises();
}

afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  document.body.innerHTML = '';
});

describe('DishRoulette', () => {
  it('renders a dialog with a spin control and traps initial focus inside', async () => {
    await mountRoulette();
    expect(dialog()).not.toBeNull();
    expect(dialog()!.getAttribute('role')).toBe('dialog');
    expect(document.activeElement).not.toBe(document.body);
    expect(dialog()!.contains(document.activeElement)).toBe(true);
  });

  it('produces a deterministic result from an injected rng', async () => {
    await mountRoulette({ rng: () => 0.99 });
    spinButton().click();
    await settle();
    expect(resultCard()).not.toBeNull();
    expect(resultCard()!.textContent).toContain('菜C');
  });

  it('announces the result via a live region', async () => {
    await mountRoulette({ rng: () => 0 });
    spinButton().click();
    await settle();
    const status = document.body.querySelector('[role="status"]');
    expect(status?.textContent).toContain('菜A');
  });

  it('ignores rapid double clicks while spinning', async () => {
    const rng = vi.fn(() => 0);
    await mountRoulette({ rng });
    spinButton().click();
    spinButton().click();
    spinButton().click();
    await settle();
    expect(rng).toHaveBeenCalledTimes(1);
  });

  it('lets the user draw again after a result', async () => {
    const values = [0, 0.5];
    let call = 0;
    await mountRoulette({ rng: () => values[call++] ?? 0 });
    spinButton().click();
    await settle();
    expect(resultCard()!.textContent).toContain('菜A');
    document.body.querySelector<HTMLButtonElement>('.roulette-again')!.click();
    await settle();
    expect(resultCard()!.textContent).toContain('菜B');
  });

  it('emits open with the drawn dish', async () => {
    const w = await mountRoulette({ rng: () => 0.5 });
    spinButton().click();
    await settle();
    document.body.querySelector<HTMLButtonElement>('.roulette-open')!.click();
    const emitted = w.emitted('open');
    expect(emitted).toHaveLength(1);
    expect((emitted![0][0] as Dish).slug).toBe('dish-b');
  });

  it('closes on Escape and on backdrop click', async () => {
    const w = await mountRoulette();
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(w.emitted('close')).toHaveLength(1);
    document.body.querySelector<HTMLElement>('.roulette-backdrop')!.dispatchEvent(new MouseEvent('click'));
    expect(w.emitted('close')).toHaveLength(2);
  });

  it('disables spinning with an empty pool and explains why', async () => {
    await mountRoulette({ dishes: [] });
    expect(spinButton().disabled).toBe(true);
    expect(dialog()!.textContent).toContain('还没有可抽的菜');
  });

  it('keeps 44px touch contract on all controls', async () => {
    await mountRoulette({ rng: () => 0 });
    spinButton().click();
    await settle();
    document.body.querySelectorAll<HTMLButtonElement>('.roulette-dialog button').forEach((btn) => {
      expect(btn.classList.contains('tap-44')).toBe(true);
    });
  });
});
