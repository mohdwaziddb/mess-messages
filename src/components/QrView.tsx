import React, { useEffect, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';
import Svg, { Path, Rect } from 'react-native-svg';
import { Mesh } from '../native/mesh';

/** Renders a QR matrix produced on-device by ZXing (no network, no JS QR library). */
export function QrView({ value, size = 240 }: { value: string; size?: number }) {
  const [m, setM] = useState<{ size: number; bits: string } | null>(null);
  useEffect(() => { let live = true; Mesh.qrMatrix(value).then(r => live && setM(r)).catch(() => {}); return () => { live = false; }; }, [value]);
  if (!m) return <View style={{ width: size, height: size, alignItems: 'center', justifyContent: 'center' }}><ActivityIndicator /></View>;
  const quiet = 2;
  const n = m.size + quiet * 2;
  let d = '';
  for (let y = 0; y < m.size; y++) for (let x = 0; x < m.size; x++) if (m.bits[y * m.size + x] === '1') d += `M${x + quiet} ${y + quiet}h1v1h-1z`;
  return (
    <Svg width={size} height={size} viewBox={`0 0 ${n} ${n}`}>
      <Rect x={0} y={0} width={n} height={n} fill="#FFFFFF" />
      <Path d={d} fill="#0B0E14" />
    </Svg>
  );
}
