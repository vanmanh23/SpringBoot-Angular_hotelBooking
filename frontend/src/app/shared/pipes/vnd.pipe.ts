import { Pipe, PipeTransform } from '@angular/core';

const FORMAT = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });

/** 1500000 -> "1.500.000 ₫". Display only: amounts always come from the backend (BR-12). */
@Pipe({ name: 'vnd' })
export class VndPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    return value === null || value === undefined ? '' : FORMAT.format(value);
  }
}
