import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PaginationNav from '../components/PaginationNav.vue'

describe('PaginationNav', () => {
  it('shows every page when there are no more than three pages', () => {
    const wrapper = mount(PaginationNav, { props: { page: 0, totalPages: 3 } })

    expect(wrapper.findAll('.pagination-page').map(button => button.text())).toEqual(['1', '2', '3'])
    expect(wrapper.find('.pagination-ellipsis').exists()).toBe(false)
  })

  it('shows the first pages, an ellipsis, and the final page when there are more than three pages', () => {
    const wrapper = mount(PaginationNav, { props: { page: 0, totalPages: 8 } })

    expect(wrapper.findAll('.pagination-page').map(button => button.text())).toEqual(['1', '2', '8'])
    expect(wrapper.find('.pagination-ellipsis').text()).toBe('…')
  })

  it('emits a zero-based page when a page number or jump target is selected', async () => {
    const wrapper = mount(PaginationNav, { props: { page: 0, totalPages: 8 } })

    await wrapper.get('[aria-label="第 2 页"]').trigger('click')
    await wrapper.get('[aria-label="跳转页码"]').setValue('7')
    await wrapper.get('.pagination-jump').trigger('submit')

    expect(wrapper.emitted('change')).toEqual([[1], [6]])
  })

  it('clamps jump targets to the available page range', async () => {
    const wrapper = mount(PaginationNav, { props: { page: 1, totalPages: 3 } })

    await wrapper.get('[aria-label="跳转页码"]').setValue('99')
    await wrapper.get('.pagination-jump').trigger('submit')

    expect(wrapper.emitted('change')).toEqual([[2]])
  })
})
