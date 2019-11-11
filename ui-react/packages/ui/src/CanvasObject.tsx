import { useCanvasInfo } from '@src/CanvasContext';
import React, { FunctionComponent } from 'react';

export interface ICanvasObjectProps {
  width: number;
  height: number;
  x: number;
  y: number;
}
export const CanvasObject: FunctionComponent<ICanvasObjectProps> = ({ children, width, height, x, y }) => {
  const { xDomain, yDomain } = useCanvasInfo();

  return (
    <foreignObject
      width={xDomain.invert(width)}
      height={yDomain.invert(height)}
      x={xDomain.invert(x)}
      y={yDomain.invert(y)}
    >
      {children}
    </foreignObject>
  );
};
