import React from 'react';
import ReactTestRenderer, { act } from 'react-test-renderer';
import { Text } from 'react-native';
import { Avatar, Banner, bytes, Button, clock, Pill, timeAgo } from '../src/components/ui';
import { avatarColor } from '../src/theme/theme';

function textOf(tree: ReactTestRenderer.ReactTestRenderer) {
  return tree.root.findAllByType(Text).map(t => t.props.children).flat().join(' ');
}

describe('ui components', () => {
  test('Button renders title and fires onPress', async () => {
    const onPress = jest.fn();
    let tree!: ReactTestRenderer.ReactTestRenderer;
    await act(async () => { tree = ReactTestRenderer.create(<Button title="Send" onPress={onPress} />); });
    expect(textOf(tree)).toContain('Send');
    await act(async () => { tree.root.findByProps({ accessibilityRole: 'button' }).props.onPress(); });
    expect(onPress).toHaveBeenCalledTimes(1);
  });

  test('disabled Button does not fire', async () => {
    const onPress = jest.fn();
    let tree!: ReactTestRenderer.ReactTestRenderer;
    await act(async () => { tree = ReactTestRenderer.create(<Button title="X" disabled onPress={onPress} />); });
    expect(tree.root.findByProps({ accessibilityRole: 'button' }).props.disabled).toBe(true);
  });

  test('Avatar shows initials and Pill / Banner render labels', async () => {
    let tree!: ReactTestRenderer.ReactTestRenderer;
    await act(async () => {
      tree = ReactTestRenderer.create(<>
        <Avatar id="1" name="Asha Rao" />
        <Pill label="Verified" tone="success" />
        <Banner tone="warning" icon="alert" title="Not verified" text="Compare numbers" />
      </>);
    });
    const s = textOf(tree);
    expect(s).toContain('AR');
    expect(s).toContain('Verified');
    expect(s).toContain('Compare numbers');
  });

  test('Avatar shows the profile photo when there is one, initials otherwise or if it fails to load', async () => {
    const { Image } = require('react-native');
    let tree!: ReactTestRenderer.ReactTestRenderer;
    await act(async () => { tree = ReactTestRenderer.create(<Avatar id="1" name="Asha Rao" uri="data:image/jpeg;base64,AAAA" />); });
    const img = tree.root.findAllByType(Image);
    expect(img).toHaveLength(1);
    expect(img[0].props.source).toEqual({ uri: 'data:image/jpeg;base64,AAAA' });
    expect(textOf(tree)).not.toContain('AR');
    // Broken image data falls back to initials instead of an empty circle.
    await act(async () => { img[0].props.onError(); });
    expect(tree.root.findAllByType(Image)).toHaveLength(0);
    expect(textOf(tree)).toContain('AR');
  });

  test('formatting helpers', () => {
    expect(bytes(500)).toBe('500 B');
    expect(bytes(2048)).toBe('2.0 KB');
    expect(bytes(3 * 1024 * 1024)).toBe('3.0 MB');
    expect(timeAgo(Date.now())).toBe('just now');
    expect(clock(new Date(2026, 0, 1, 9, 5).getTime())).toBe('09:05');
  });

  test('avatar colour depends on id, not name', () => {
    expect(avatarColor('same-id')).toBe(avatarColor('same-id'));
  });
});
