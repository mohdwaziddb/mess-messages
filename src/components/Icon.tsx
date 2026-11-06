import React from 'react';
import Svg, { Circle, Path, Polyline, Rect, Line } from 'react-native-svg';

/** Consistent 24px stroke icon set (Feather-style geometry), drawn locally — no icon font downloads. */
export type IconName =
  | 'home' | 'chat' | 'radar' | 'shield' | 'settings' | 'qr' | 'scan' | 'plus' | 'send' | 'back' | 'check'
  | 'checks' | 'clock' | 'alert' | 'lock' | 'users' | 'user' | 'file' | 'trash' | 'chevron' | 'bluetooth'
  | 'battery' | 'refresh' | 'mesh' | 'paperclip' | 'x' | 'eye' | 'eyeOff' | 'database' | 'help' | 'key' | 'download'
  | 'slash' | 'edit' | 'logout' | 'wifi';

export function Icon({ name, size = 22, color = '#fff', strokeWidth = 2 }: { name: IconName; size?: number; color?: string; strokeWidth?: number }) {
  const p = { stroke: color, strokeWidth, fill: 'none', strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };
  const body = (() => {
    switch (name) {
      case 'home': return <><Path {...p} d="M3 10.5 12 3l9 7.5" /><Path {...p} d="M5 9.5V20h5v-6h4v6h5V9.5" /></>;
      case 'chat': return <Path {...p} d="M21 12a8 8 0 0 1-11.6 7.1L4 20.5l1.4-4.6A8 8 0 1 1 21 12z" />;
      case 'radar': return <><Circle {...p} cx={12} cy={12} r={9} /><Circle {...p} cx={12} cy={12} r={5} /><Circle cx={12} cy={12} r={1.6} fill={color} /><Path {...p} d="M12 12 18.5 5.5" /></>;
      case 'shield': return <><Path {...p} d="M12 3 4.5 6v5.5c0 4.6 3.2 8.3 7.5 9.5 4.3-1.2 7.5-4.9 7.5-9.5V6z" /><Polyline {...p} points="9 12 11.2 14.2 15.5 9.8" /></>;
      case 'settings': return <><Circle {...p} cx={12} cy={12} r={3} /><Path {...p} d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" /></>;
      case 'qr': return <><Rect {...p} x={3} y={3} width={7} height={7} rx={1} /><Rect {...p} x={14} y={3} width={7} height={7} rx={1} /><Rect {...p} x={3} y={14} width={7} height={7} rx={1} /><Path {...p} d="M14 14h3v3h-3zM20 14v.01M17 20h4M14 20v.01M20 17v3" /></>;
      case 'scan': return <><Path {...p} d="M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2" /><Line {...p} x1={7} y1={12} x2={17} y2={12} /></>;
      case 'plus': return <><Line {...p} x1={12} y1={5} x2={12} y2={19} /><Line {...p} x1={5} y1={12} x2={19} y2={12} /></>;
      case 'send': return <><Path {...p} d="M22 2 11 13" /><Path {...p} d="M22 2 15 22l-4-9-9-4z" /></>;
      case 'back': return <Polyline {...p} points="15 18 9 12 15 6" />;
      case 'chevron': return <Polyline {...p} points="9 18 15 12 9 6" />;
      case 'check': return <Polyline {...p} points="20 6 9 17 4 12" />;
      case 'checks': return <><Polyline {...p} points="17 7 8 16 4 12" /><Polyline {...p} points="22 7 13 16 11.5 14.5" /></>;
      case 'clock': return <><Circle {...p} cx={12} cy={12} r={9} /><Polyline {...p} points="12 7 12 12 15 14" /></>;
      case 'alert': return <><Path {...p} d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" /><Line {...p} x1={12} y1={9} x2={12} y2={13} /><Line {...p} x1={12} y1={17} x2={12.01} y2={17} /></>;
      case 'lock': return <><Rect {...p} x={4} y={11} width={16} height={10} rx={2} /><Path {...p} d="M8 11V7a4 4 0 0 1 8 0v4" /></>;
      case 'key': return <><Circle {...p} cx={7.5} cy={15.5} r={4.5} /><Path {...p} d="m10.7 12.3 9.3-9.3M17 6l3 3M14.5 8.5l2 2" /></>;
      case 'users': return <><Path {...p} d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><Circle {...p} cx={9} cy={7} r={4} /><Path {...p} d="M23 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8" /></>;
      case 'user': return <><Path {...p} d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" /><Circle {...p} cx={12} cy={7} r={4} /></>;
      case 'file': return <><Path {...p} d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><Polyline {...p} points="14 2 14 8 20 8" /></>;
      case 'paperclip': return <Path {...p} d="m21.4 11.1-9.2 9.2a6 6 0 0 1-8.5-8.5l9.2-9.2a4 4 0 0 1 5.7 5.7l-9.2 9.2a2 2 0 0 1-2.8-2.8l8.5-8.5" />;
      case 'trash': return <><Polyline {...p} points="3 6 5 6 21 6" /><Path {...p} d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 11v6M14 11v6M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" /></>;
      case 'bluetooth': return <Polyline {...p} points="6.5 6.5 17.5 17.5 12 23 12 1 17.5 6.5 6.5 17.5" />;
      case 'wifi': return <><Path {...p} d="M5 12.6a10 10 0 0 1 14 0M8.5 16.1a5 5 0 0 1 7 0M2 8.8a15 15 0 0 1 20 0" /><Line {...p} x1={12} y1={20} x2={12.01} y2={20} /></>;
      case 'battery': return <><Rect {...p} x={1} y={6} width={18} height={12} rx={2} /><Line {...p} x1={23} y1={13} x2={23} y2={11} /><Line {...p} x1={5} y1={10} x2={5} y2={14} /><Line {...p} x1={9} y1={10} x2={9} y2={14} /></>;
      case 'refresh': return <><Polyline {...p} points="23 4 23 10 17 10" /><Path {...p} d="M20.5 15a9 9 0 1 1-2.1-9.4L23 10" /></>;
      case 'mesh': return <><Circle {...p} cx={12} cy={5} r={2.5} /><Circle {...p} cx={5} cy={18} r={2.5} /><Circle {...p} cx={19} cy={18} r={2.5} /><Path {...p} d="M10.8 7.2 6.3 15.8M13.2 7.2l4.5 8.6M7.5 18h9" /></>;
      case 'x': return <><Line {...p} x1={18} y1={6} x2={6} y2={18} /><Line {...p} x1={6} y1={6} x2={18} y2={18} /></>;
      case 'eye': return <><Path {...p} d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8S1 12 1 12z" /><Circle {...p} cx={12} cy={12} r={3} /></>;
      case 'eyeOff': return <><Path {...p} d="M17.9 17.9A10 10 0 0 1 12 20c-7 0-11-8-11-8a18.5 18.5 0 0 1 5.1-5.9M9.9 4.2A9 9 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.2 3.2M1 1l22 22" /></>;
      case 'database': return <><Path {...p} d="M3 5c0-1.7 4-3 9-3s9 1.3 9 3-4 3-9 3-9-1.3-9-3z" /><Path {...p} d="M21 12c0 1.7-4 3-9 3s-9-1.3-9-3M3 5v14c0 1.7 4 3 9 3s9-1.3 9-3V5" /></>;
      case 'help': return <><Circle {...p} cx={12} cy={12} r={9} /><Path {...p} d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3" /><Line {...p} x1={12} y1={17} x2={12.01} y2={17} /></>;
      case 'download': return <><Path {...p} d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><Polyline {...p} points="7 10 12 15 17 10" /><Line {...p} x1={12} y1={15} x2={12} y2={3} /></>;
      case 'slash': return <><Circle {...p} cx={12} cy={12} r={9} /><Line {...p} x1={5.6} y1={5.6} x2={18.4} y2={18.4} /></>;
      case 'edit': return <Path {...p} d="M17 3a2.8 2.8 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z" />;
      case 'logout': return <><Path {...p} d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><Polyline {...p} points="16 17 21 12 16 7" /><Line {...p} x1={21} y1={12} x2={9} y2={12} /></>;
    }
  })();
  return <Svg width={size} height={size} viewBox="0 0 24 24">{body}</Svg>;
}
